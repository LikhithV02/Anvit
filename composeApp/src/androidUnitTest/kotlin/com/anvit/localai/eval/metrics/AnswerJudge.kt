package com.anvit.localai.eval.metrics

import com.anvit.localai.agentic.PipelineTrace
import com.anvit.localai.eval.dataset.EvalSample
import com.anvit.localai.eval.services.BatchTextRequest
import com.anvit.localai.eval.services.GeminiClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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
        parseValidated(client.readJudgeCache(key))?.let { return it }
        return requestImmediateScore(sample.id, prompt, key)
    }

    fun scoreBatch(items: List<Pair<EvalSample, PipelineTrace>>): Map<String, AnswerJudgeScore> {
        if (items.isEmpty()) return emptyMap()
        println("[AnswerJudge] Preparing batch judge prompts: ${items.size} samples")
        println("[AnswerJudge] Gemini structured output schema enabled for batch judging ($CACHE_VERSION)")
        val prompts = items.associate { (sample, trace) -> sample.id to buildPrompt(sample, trace) }
        val cachedRaw = prompts.mapValues { (_, prompt) -> client.readJudgeCache(judgeCacheKey(prompt)) }
        val cacheHits = cachedRaw.count { (_, raw) -> raw != null }
        val requests = prompts.mapNotNull { (sampleId, prompt) ->
            if (cachedRaw[sampleId] != null) null
            else BatchTextRequest(
                key = sampleId,
                prompt = prompt,
                systemPrompt = SYSTEM_PROMPT,
                responseSchema = ANSWER_JUDGE_SCHEMA
            )
        }
        println("[AnswerJudge] Judge cache: $cacheHits hits, ${requests.size} misses")
        val freshRawById = client.batchGenerateText(
            requests = requests,
            useJudgeModel = true,
            displayName = "anvit-answer-judge-${System.currentTimeMillis()}"
        )
        println("[AnswerJudge] Batch judge returned ${freshRawById.size}/${requests.size} fresh responses")
        val cachedScores = prompts.keys.mapNotNull { sampleId ->
            parseValidated(cachedRaw[sampleId])?.let { sampleId to it }
        }.toMap()
        val freshScores = freshRawById.mapNotNull { (sampleId, raw) ->
            val parsed = parseValidated(raw)
            if (parsed != null) {
                prompts[sampleId]?.let { prompt -> client.writeJudgeCache(judgeCacheKey(prompt), raw) }
                sampleId to parsed
            } else {
                println("[AnswerJudge] Batch judge output invalid for $sampleId; retrying immediately")
                null
            }
        }.toMap()
        val fallbackScores = items.mapNotNull { (sample, trace) ->
            if (sample.id in cachedScores || sample.id in freshScores) null
            else sample.id to requestImmediateScore(sample.id, prompts.getValue(sample.id), judgeCacheKey(prompts.getValue(sample.id)))
        }.toMap()
        return items.associate { (sample, trace) ->
            sample.id to (cachedScores[sample.id]
                ?: freshScores[sample.id]
                ?: fallbackScores[sample.id]
                ?: invalidScore("Judge output missing after retries for ${sample.id}", trace.generatedAnswer))
        }.also { println("[AnswerJudge] Parsed judge scores: ${it.size}/${items.size}") }
    }

    private fun buildPrompt(sample: EvalSample, trace: PipelineTrace): String {
        val context = trace.finalChunks.joinToString("\n\n---\n\n") {
            "[${it.chunkId} | ${it.fileName}]\n${it.contentPreview}"
        }
        return """
You are judging an offline evaluation run for a document RAG system.
Return a JSON object matching the provided response schema.

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

    private fun requestImmediateScore(sampleId: String, prompt: String, cacheKey: String): AnswerJudgeScore {
        var lastRaw = ""
        repeat(IMMEDIATE_RETRY_ATTEMPTS) { attempt ->
            println("[AnswerJudge] Immediate judge request: $sampleId (attempt ${attempt + 1}/$IMMEDIATE_RETRY_ATTEMPTS)")
            val raw = client.generateText(
                prompt = prompt,
                systemPrompt = SYSTEM_PROMPT,
                useJudgeModel = true,
                responseSchema = ANSWER_JUDGE_SCHEMA
            )
            lastRaw = raw
            parseValidated(raw)?.let { parsed ->
                client.writeJudgeCache(cacheKey, raw)
                println("[AnswerJudge] Immediate judge response cached: $sampleId")
                return parsed
            }
            println("[AnswerJudge] Immediate judge output invalid for $sampleId: ${raw.take(200)}")
        }
        return invalidScore("Judge output invalid after retries for $sampleId", lastRaw)
    }

    private fun parseValidated(raw: String?): AnswerJudgeScore? {
        if (raw.isNullOrBlank()) return null
        val payload = extractJsonObject(raw) ?: return null
        val score = runCatching {
            json.decodeFromJsonElement(AnswerJudgeScore.serializer(), payload)
        }.getOrNull() ?: return null
        if (!hasRequiredFields(payload)) return null
        if (score.rationale.isBlank()) return null
        if (!isUnitScore(score.faithfulness)) return null
        if (!isUnitScore(score.correctness)) return null
        if (!isUnitScore(score.citationCorrectness)) return null
        if (!isUnitScore(score.refusalCorrectness)) return null
        return score
    }

    private fun extractJsonObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.getOrNull()
    }

    private fun hasRequiredFields(payload: JsonObject): Boolean =
        REQUIRED_FIELDS.all(payload::containsKey)

    private fun isUnitScore(value: Double): Boolean = value in 0.0..1.0

    private fun invalidScore(reason: String, raw: String): AnswerJudgeScore =
        AnswerJudgeScore(rationale = "$reason. Raw: ${raw.take(300)}")

    private companion object {
        const val SYSTEM_PROMPT = "You are a strict RAG evaluator. Use the structured JSON schema exactly."
        const val CACHE_VERSION = "structured-v1"
        const val IMMEDIATE_RETRY_ATTEMPTS = 2
        val REQUIRED_FIELDS = setOf(
            "faithfulness",
            "correctness",
            "citationCorrectness",
            "refusalCorrectness",
            "rationale"
        )
        val ANSWER_JUDGE_SCHEMA = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("faithfulness", numberProperty("0.0 to 1.0 score for whether the answer only uses retrieved context."))
                put("correctness", numberProperty("0.0 to 1.0 score for semantic correctness against the gold answer."))
                put("citationCorrectness", numberProperty("0.0 to 1.0 score for source citation correctness."))
                put("refusalCorrectness", numberProperty("0.0 to 1.0 score for whether refusal behavior matches expected_refusal."))
                put("rationale", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Short reason for the scores."))
                })
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("faithfulness"))
                add(JsonPrimitive("correctness"))
                add(JsonPrimitive("citationCorrectness"))
                add(JsonPrimitive("refusalCorrectness"))
                add(JsonPrimitive("rationale"))
            })
        }

        fun numberProperty(description: String) = buildJsonObject {
            put("type", JsonPrimitive("number"))
            put("description", JsonPrimitive(description))
        }
    }

    private fun judgeCacheKey(prompt: String): String = client.cacheKey("$CACHE_VERSION\n$SYSTEM_PROMPT\n\n$prompt")
}
