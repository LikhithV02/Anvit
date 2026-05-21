package com.anvit.localai.eval.dataset

import com.anvit.localai.eval.services.GeminiClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class DatasetGenerator(
    private val client: GeminiClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val workerCount = (System.getProperty("anvit.eval.datasetWorkers")?.toIntOrNull() ?: 4).coerceIn(1, 8)

    fun generateFromCsvExports(
        csvDir: File,
        output: File,
        singleHop: Int = 20,
        multiHop: Int = 20,
        tableLookup: Int = 10,
        adversarial: Int = 10
    ): EvalDataset {
        val chunks = csvDir.listFiles { file -> file.extension.equals("csv", true) }.orEmpty()
            .flatMap { readChunkCsv(it) }
        require(chunks.isNotEmpty()) { "No chunk CSV rows found in ${csvDir.absolutePath}" }

        val sampler = ChunkSampler(chunks)
        println("[DatasetGenerator] Loaded ${chunks.size} chunks from ${csvDir.absolutePath}")
        val generationJobs =
            sampler.singleHop(singleHop).mapIndexed { index, sampled -> { generateSampleOrNull("single-$index", EvalQuestionType.SINGLE_HOP, sampled) } } +
                sampler.multiHop(multiHop).mapIndexed { index, sampled -> { generateSampleOrNull("multi-$index", EvalQuestionType.MULTI_HOP, sampled) } } +
                sampler.tableLookup(tableLookup).mapIndexed { index, sampled -> { generateSampleOrNull("table-$index", EvalQuestionType.TABLE_LOOKUP, sampled) } } +
                sampler.adversarial(adversarial).mapIndexed { index, _ -> { generateAdversarialOrNull("adv-$index") } }

        val candidates = runParallel("Generating", generationJobs)

        println("[DatasetGenerator] Generated ${candidates.size} candidate samples; validating...")
        val validator = DatasetValidator(client)
        val valid = runParallel("Validating", candidates.mapIndexed { index, sample ->
            {
                println("[DatasetGenerator] Validating ${index + 1}/${candidates.size}: ${sample.id}")
                sample.takeIf { validator.isValid(it, chunks) }
            }
        })
        val dataset = EvalDataset(
            version = datasetVersion(output),
            description = "Gemini-generated Anvit Agentic RAG eval dataset with natural user-style questions.",
            samples = valid
        )
        EvalDataset.write(output, dataset)
        return dataset
    }

    private fun <T : Any> runParallel(label: String, jobs: List<() -> T?>): List<T> {
        if (jobs.isEmpty()) return emptyList()
        println("[DatasetGenerator] $label ${jobs.size} jobs with $workerCount worker(s)")
        val executor = Executors.newFixedThreadPool(workerCount)
        return try {
            executor.invokeAll(jobs.mapIndexed { index, job ->
                Callable {
                    val result = job()
                    if ((index + 1) % 10 == 0 || index == jobs.lastIndex) {
                        println("[DatasetGenerator] $label progress: ${index + 1}/${jobs.size}")
                    }
                    result
                }
            }).mapNotNull { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun generateSample(id: String, type: EvalQuestionType, chunks: List<ChunkRecord>): EvalSample {
        println("[DatasetGenerator] Generating $id ($type) from ${chunks.size} chunk(s)")
        val context = chunks.joinToString("\n---\n") { "[${it.chunkId} | ${it.fileName}]\n${it.content.take(1600)}" }
        val raw = client.generateText(
            prompt = """
Create one realistic user question and answer for a document-chat app from this evidence.
The user should sound like a normal person asking about uploaded business or financial documents.
Do not mention "chunks", "provided chunks", "expected chunks", or internal eval mechanics.
Avoid artificial labels like "Col 2" unless the label is the only visible way to identify the table cell.
Prefer natural wording such as "What did the company report for..." or "How did ... change, and why?"
The answer must be fully supported by the evidence. Return JSON only:
{"question":"...","goldAnswer":"...","rationale":"...","difficulty":"easy|medium|hard"}

Type: $type
Chunks:
$context
            """.trimIndent(),
            systemPrompt = "You generate precise RAG evaluation data."
        )
        val parsed = parseGenerated(raw)
        return EvalSample(
            id = id,
            type = type,
            question = parsed.question,
            expectedChunkIds = chunks.map { it.chunkId },
            expectedContentHashes = chunks.map { stableContentHash(it.content) },
            expectedDocIds = chunks.map { it.docId }.distinct(),
            goldAnswer = parsed.goldAnswer,
            rationale = parsed.rationale,
            difficulty = parsed.difficulty,
            expectedRoute = if (type == EvalQuestionType.SINGLE_HOP) "SINGLE_SHOT" else "AGENTIC",
            metadata = sampleMetadata(type, parsed.question, chunks)
        )
    }

    private fun generateSampleOrNull(id: String, type: EvalQuestionType, chunks: List<ChunkRecord>): EvalSample? =
        runCatching { generateSample(id, type, chunks) }
            .map { sample ->
                require(isNaturalQuestion(sample.question)) {
                    "Generated question uses synthetic phrasing: ${sample.question}"
                }
                sample
            }
            .onFailure { println("[DatasetGenerator] Dropping $id after generation failure: ${it.message}") }
            .getOrNull()

    private fun generateAdversarial(id: String): EvalSample {
        println("[DatasetGenerator] Generating $id (ADVERSARIAL)")
        val question = client.generateText(
            prompt = "Write one natural, plausible user question for a document-chat app that cannot be answered from a typical company financial report. Return only the question.",
            systemPrompt = "You generate adversarial RAG evaluation questions."
        ).trim()
        return EvalSample(
            id = id,
            type = EvalQuestionType.ADVERSARIAL,
            question = question,
            expectedChunkIds = emptyList(),
            expectedDocIds = emptyList(),
            goldAnswer = "The corpus does not contain enough information to answer.",
            rationale = "Adversarial sample with no linked evidence chunks.",
            difficulty = "medium",
            expectedRoute = "SINGLE_SHOT",
            expectedRefusal = true,
            metadata = mapOf(
                "question_style" to "natural",
                "source_type" to "adversarial",
                "requires_calculation" to "false"
            )
        )
    }

    private fun generateAdversarialOrNull(id: String): EvalSample? =
        runCatching { generateAdversarial(id) }
            .onFailure { println("[DatasetGenerator] Dropping $id after generation failure: ${it.message}") }
            .getOrNull()

    private fun parseGenerated(raw: String): GeneratedItem {
        val jsonText = raw.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
        return runCatching {
            json.decodeFromString(GeneratedItem.serializer(), jsonText)
        }.getOrElse {
            val repaired = jsonText.replace(Regex("""\\([^"\\/bfnrtu])"""), "$1")
            json.decodeFromString(GeneratedItem.serializer(), repaired)
        }
    }

    private fun readChunkCsv(file: File): List<ChunkRecord> {
        val rows = parseCsvRows(file.readText()).drop(1)
        val docId = file.name.removePrefix("chunks_export_").removeSuffix(".csv")
        return rows.mapIndexed { index, columns ->
            ChunkRecord(
                chunkId = columns.getOrNull(0)?.ifBlank { "$docId-$index" } ?: "$docId-$index",
                docId = docId,
                fileName = docId,
                groupId = columns.getOrNull(2)?.takeIf { it.isNotBlank() },
                hierarchyPath = columns.getOrNull(4).orEmpty(),
                content = columns.getOrNull(5).orEmpty()
            )
        }.filter { it.content.isNotBlank() }
    }

    private fun datasetVersion(output: File): String =
        output.parentFile?.name?.takeIf { it.startsWith("v") } ?: "v2"

    private fun sampleMetadata(
        type: EvalQuestionType,
        question: String,
        chunks: List<ChunkRecord>
    ): Map<String, String> {
        val sourceType = when (type) {
            EvalQuestionType.TABLE_LOOKUP -> "table"
            EvalQuestionType.MULTI_HOP -> if (chunks.map { it.docId }.distinct().size > 1) "multi_doc" else "narrative"
            EvalQuestionType.SINGLE_HOP -> if (chunks.any { isTableChunk(it.content) }) "table" else "narrative"
            EvalQuestionType.ADVERSARIAL -> "adversarial"
        }
        return mapOf(
            "question_style" to "natural",
            "source_type" to sourceType,
            "requires_calculation" to requiresCalculation(question).toString()
        )
    }

    private fun isTableChunk(content: String): Boolean =
        content.lines().any { line -> line.trim().startsWith("|") }

    private fun requiresCalculation(question: String): Boolean {
        val q = question.lowercase()
        return listOf("sum", "combined", "total of", "difference", "increase", "decrease", "highest", "lowest", "largest", "smallest")
            .any { q.contains(it) }
    }

    private fun isNaturalQuestion(question: String): Boolean {
        val q = question.lowercase()
        val banned = listOf(
            "provided chunks",
            "expected chunks",
            "chunk 1",
            "chunk 2",
            "first chunk",
            "second chunk",
            "associated numerical identifier",
            "based on the provided documents"
        )
        if (banned.any { q.contains(it) }) return false
        val colReferences = Regex("\\bcol\\s*\\d+\\b", RegexOption.IGNORE_CASE).findAll(question).count()
        return colReferences <= 1
    }

    private fun stableContentHash(content: String): String {
        var hash = -3750763034362895579L
        val prime = 1099511628211L
        content.trim().lowercase().encodeToByteArray().forEach { byte ->
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= prime
        }
        return hash.toString(16)
    }

    private fun parseCsvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentCell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> {
                    currentCell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    currentRow.add(currentCell.toString())
                    currentCell.clear()
                }
                (c == '\n' || c == '\r') && !quoted -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    currentRow.add(currentCell.toString())
                    currentCell.clear()
                    if (currentRow.any { it.isNotBlank() }) rows.add(currentRow.toList())
                    currentRow.clear()
                }
                else -> currentCell.append(c)
            }
            i++
        }
        if (currentCell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentCell.toString())
            if (currentRow.any { it.isNotBlank() }) rows.add(currentRow.toList())
        }
        return rows
    }

}

@Serializable
private data class GeneratedItem(
    val question: String,
    val goldAnswer: String,
    val rationale: String = "",
    val difficulty: String = "medium"
)
