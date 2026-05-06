package com.anvit.localai.eval.metrics

import com.anvit.localai.agentic.PipelineTrace
import com.anvit.localai.eval.dataset.EvalSample
import com.anvit.localai.eval.services.BatchTextRequest
import com.anvit.localai.eval.services.GeminiClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AnswerJudgeScore(
    val faithfulness: Double = 0.0,
    val correctness: Double = 0.0,
    val citationCorrectness: Double = 0.0,
    val refusalCorrectness: Double = 0.0,
    val rationale: String = ""
)

class AnswerJudge(
    private val client: GeminiClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun score(sample: EvalSample, trace: PipelineTrace): AnswerJudgeScore {
        val prompt = buildPrompt(sample, trace)
        val key = judgeCacheKey(prompt)
        val raw = client.readJudgeCache(key) ?: client.generateText(
            prompt = prompt,
            systemPrompt = SYSTEM_PROMPT,
            useJudgeModel = true
        ).also { client.writeJudgeCache(key, it) }
        return parse(raw)
    }

    fun scoreBatch(items: List<Pair<EvalSample, PipelineTrace>>): Map<String, AnswerJudgeScore> {
        if (items.isEmpty()) return emptyMap()
        val prompts = items.associate { (sample, trace) -> sample.id to buildPrompt(sample, trace) }
        val cachedRaw = prompts.mapValues { (_, prompt) -> client.readJudgeCache(judgeCacheKey(prompt)) }
        val requests = prompts.mapNotNull { (sampleId, prompt) ->
            if (cachedRaw[sampleId] != null) null
            else BatchTextRequest(
                key = sampleId,
                prompt = prompt,
                systemPrompt = SYSTEM_PROMPT
            )
        }
        val freshRawById = client.batchGenerateText(
            requests = requests,
            useJudgeModel = true,
            displayName = "anvit-answer-judge-${System.currentTimeMillis()}"
        )
        freshRawById.forEach { (sampleId, raw) ->
            prompts[sampleId]?.let { prompt -> client.writeJudgeCache(judgeCacheKey(prompt), raw) }
        }
        val rawById = prompts.keys.associateWith { sampleId ->
            cachedRaw[sampleId] ?: freshRawById[sampleId].orEmpty()
        }
        return items.associate { (sample, _) ->
            sample.id to parse(rawById[sample.id].orEmpty())
        }
    }

    private fun buildPrompt(sample: EvalSample, trace: PipelineTrace): String {
        val context = trace.finalChunks.joinToString("\n\n---\n\n") {
            "[${it.chunkId} | ${it.fileName}]\n${it.contentPreview}"
        }
        return """
You are judging an offline evaluation run for a document RAG system.
Return only JSON with these fields:
faithfulness: number from 0.0 to 1.0
correctness: number from 0.0 to 1.0
citationCorrectness: number from 0.0 to 1.0
refusalCorrectness: number from 0.0 to 1.0
rationale: short string

Question:
${sample.question}

Gold answer:
${sample.goldAnswer}

Expected refusal: ${sample.expectedRefusal}

Retrieved context:
$context

Answer:
${trace.generatedAnswer}
        """.trimIndent()
    }

    private fun parse(raw: String): AnswerJudgeScore =
        runCatching {
            json.decodeFromString(AnswerJudgeScore.serializer(), raw.substringAfter("{").substringBeforeLast("}").let { "{$it}" })
        }.getOrElse {
            AnswerJudgeScore(rationale = "Judge parse failed: ${it.message}. Raw: ${raw.take(300)}")
        }

    private companion object {
        const val SYSTEM_PROMPT = "You are a strict RAG evaluator. Output valid JSON only."
    }

    private fun judgeCacheKey(prompt: String): String = client.cacheKey("$SYSTEM_PROMPT\n\n$prompt")
}
