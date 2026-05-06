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
            version = "v1",
            description = "Gemini-generated Anvit Agentic RAG eval dataset.",
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
Create one document QA eval item from these chunks.
The question must require exactly the provided chunks. Return JSON only:
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
            expectedRoute = if (type == EvalQuestionType.SINGLE_HOP) "SINGLE_SHOT" else "AGENTIC"
        )
    }

    private fun generateSampleOrNull(id: String, type: EvalQuestionType, chunks: List<ChunkRecord>): EvalSample? =
        runCatching { generateSample(id, type, chunks) }
            .onFailure { println("[DatasetGenerator] Dropping $id after generation failure: ${it.message}") }
            .getOrNull()

    private fun generateAdversarial(id: String): EvalSample {
        println("[DatasetGenerator] Generating $id (ADVERSARIAL)")
        val question = client.generateText(
            prompt = "Write one plausible but unanswerable business-document question. Return only the question.",
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
            expectedRefusal = true
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
