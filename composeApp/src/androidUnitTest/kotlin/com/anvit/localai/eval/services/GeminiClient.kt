package com.anvit.localai.eval.services

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GeminiClient(
    private val apiKey: String,
    private val generationModel: String = "gemini-2.5-flash",
    private val judgeModel: String = "gemini-2.5-pro",
    private val embeddingModel: String = "gemini-embedding-001",
    private val batchPollIntervalMs: Long = 30_000L,
    private val batchTimeoutMs: Long = 24L * 60L * 60L * 1_000L
) {
    private val cacheDir: File = run {
        val configured = System.getProperty("anvit.eval.cacheDir")
        if (!configured.isNullOrBlank()) {
            File(configured)
        } else {
            File((File(System.getProperty("user.dir") ?: ".").parentFile ?: File(".")), "eval/.cache/gemini")
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(120, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
            }
        }
    }

    fun generateText(prompt: String, systemPrompt: String? = null, useJudgeModel: Boolean = false): String {
        val model = if (useJudgeModel) judgeModel else generationModel
        val body = generationRequest(prompt, systemPrompt)
        val response = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", body)
        return extractText(response)
    }

    fun streamText(prompt: String, systemPrompt: String? = null): Flow<String> = flow {
        val reqBody = json.encodeToString(JsonObject.serializer(), generationRequest(prompt, systemPrompt))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$generationModel:streamGenerateContent?alt=sse"
        httpClient.preparePost(url) {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(reqBody)
        }.execute { httpResponse ->
            if (httpResponse.status.value !in 200..299) {
                error("Gemini stream returned HTTP ${httpResponse.status.value}: ${httpResponse.bodyAsText()}")
            }
            val channel = httpResponse.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]" || data.isBlank()) continue
                val text = runCatching {
                    extractText(json.parseToJsonElement(data).jsonObject)
                }.getOrNull().orEmpty()
                if (text.isNotEmpty()) emit(text)
            }
        }
    }

    fun batchGenerateText(
        requests: List<BatchTextRequest>,
        useJudgeModel: Boolean = false,
        displayName: String = "anvit-eval-batch-${System.currentTimeMillis()}"
    ): Map<String, String> {
        if (requests.isEmpty()) return emptyMap()
        if (requests.size == 1) {
            val request = requests.first()
            return mapOf(request.key to generateText(request.prompt, request.systemPrompt, useJudgeModel))
        }

        val model = if (useJudgeModel) judgeModel else generationModel
        val body = buildJsonObject {
            put("batch", buildJsonObject {
                put("display_name", JsonPrimitive(displayName))
                put("input_config", buildJsonObject {
                    put("requests", buildJsonObject {
                        put("requests", buildJsonArray {
                            requests.forEach { item ->
                                add(buildJsonObject {
                                    put("request", generationRequest(item.prompt, item.systemPrompt))
                                    put("metadata", buildJsonObject {
                                        put("key", JsonPrimitive(item.key))
                                    })
                                })
                            }
                        })
                    })
                })
            })
        }

        val createResponse = post("https://generativelanguage.googleapis.com/v1beta/models/$model:batchGenerateContent", body)
        val jobName = createResponse["name"]?.jsonPrimitive?.contentOrNull
            ?: createResponse["batch"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            ?: error("Batch job creation response did not include a job name: $createResponse")
        val finalResponse = pollBatchJob(jobName)
        return extractInlineBatchResponses(finalResponse, requests.map { it.key })
    }

    fun embed(text: String): FloatArray {
        val cacheFile = File(cacheDir, "embeddings/$embeddingModel/${sha256(text.take(8000))}.txt")
        if (cacheFile.exists()) {
            val cached = cacheFile.readText().trim()
            if (cached.isNotBlank()) {
                return cached.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
            }
        }

        val body = buildJsonObject {
            put("model", JsonPrimitive("models/$embeddingModel"))
            put("content", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", JsonPrimitive(text.take(8000))) })
                })
            })
        }
        val response = post("https://generativelanguage.googleapis.com/v1beta/models/$embeddingModel:embedContent", body)
        val values = response["embedding"]?.jsonObject?.get("values")?.jsonArray ?: JsonArray(emptyList())
        return FloatArray(values.size) { index -> values[index].jsonPrimitive.floatOrNull ?: 0f }
            .also { embedding ->
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(embedding.joinToString(","))
            }
    }

    fun cacheKey(text: String): String = sha256(text)

    fun readJudgeCache(key: String): String? {
        val file = File(cacheDir, "judge/$judgeModel/$key.json")
        return if (file.exists()) file.readText() else null
    }

    fun writeJudgeCache(key: String, raw: String) {
        val file = File(cacheDir, "judge/$judgeModel/$key.json")
        file.parentFile?.mkdirs()
        file.writeText(raw)
    }

    private fun post(url: String, body: JsonObject): JsonObject =
        request("POST", url, json.encodeToString(JsonObject.serializer(), body))

    private fun get(url: String): JsonObject =
        request("GET", url, null)

    private fun request(method: String, url: String, body: String?): JsonObject {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val responseText = runBlocking {
                    val response = httpClient.request(url) {
                        this.method = if (method == "POST") HttpMethod.Post else HttpMethod.Get
                        header("x-goog-api-key", apiKey)
                        if (method == "POST" && body != null) {
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    }
                    val text = response.bodyAsText()
                    if (response.status.value !in 200..299) {
                        error("Gemini API returned HTTP ${response.status.value}: $text")
                    }
                    text
                }
                return json.parseToJsonElement(responseText).jsonObject
            } catch (e: Exception) {
                lastError = e
                Thread.sleep((attempt + 1) * 1_000L)
            }
        }
        throw lastError ?: IllegalStateException("Gemini API call failed")
    }

    private fun pollBatchJob(jobName: String): JsonObject {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < batchTimeoutMs) {
            val response = try {
                get("https://generativelanguage.googleapis.com/v1beta/$jobName")
            } catch (e: Exception) {
                println("[GeminiClient] Batch poll failed for $jobName; retrying: ${e.message}")
                Thread.sleep(batchPollIntervalMs)
                continue
            }
            val state = response["metadata"]?.jsonObject?.get("state")?.jsonPrimitive?.contentOrNull
                ?: response["state"]?.jsonPrimitive?.contentOrNull
                ?: response["batch"]?.jsonObject?.get("state")?.jsonPrimitive?.contentOrNull
            val done = response["done"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            if (done == true || state in terminalBatchStates) {
                if (state in failedBatchStates) error("Gemini batch job $jobName ended in $state: $response")
                return response
            }
            Thread.sleep(batchPollIntervalMs)
        }
        error("Timed out waiting for Gemini batch job $jobName")
    }

    private fun extractInlineBatchResponses(response: JsonObject, orderedKeys: List<String>): Map<String, String> {
        val inlined = firstInlineResponses(response)

        val byKey = linkedMapOf<String, String>()
        inlined.forEachIndexed { index, element ->
            val obj = element.jsonObject
            val key = obj["metadata"]?.jsonObject?.get("key")?.jsonPrimitive?.contentOrNull
                ?: obj["key"]?.jsonPrimitive?.contentOrNull
                ?: orderedKeys.getOrNull(index)
            val candidateResponse = obj["response"]?.jsonObject
                ?: obj["generateContentResponse"]?.jsonObject
                ?: obj["generate_content_response"]?.jsonObject
            if (key != null && candidateResponse != null) {
                byKey[key] = extractText(candidateResponse)
            }
        }
        return orderedKeys.associateWith { byKey[it].orEmpty() }
    }

    private fun firstInlineResponses(response: JsonObject): List<JsonElement> {
        val candidates = listOfNotNull(
            response["response"]?.jsonObject?.get("inlinedResponses"),
            response["response"]?.jsonObject?.get("inlined_responses"),
            response["batch"]?.jsonObject?.get("output")?.jsonObject?.get("inlinedResponses"),
            response["batch"]?.jsonObject?.get("output")?.jsonObject?.get("inlined_responses"),
            response["output"]?.jsonObject?.get("inlinedResponses"),
            response["output"]?.jsonObject?.get("inlined_responses")
        )
        return candidates.firstNotNullOfOrNull { element ->
            val responses = when (element) {
                is JsonArray -> element.toList()
                is JsonObject -> element.values.toList()
                else -> null
            }
            responses?.takeIf { it.isNotEmpty() }
        } ?: emptyList()
    }

    private fun generationRequest(prompt: String, systemPrompt: String? = null): JsonObject = buildJsonObject {
        systemPrompt?.takeIf { it.isNotBlank() }?.let { promptText ->
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", JsonPrimitive(promptText)) })
                })
            })
        }
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", JsonPrimitive(prompt)) })
                })
            })
        })
    }

    private fun extractText(response: JsonObject): String =
        response["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.joinToString("") { part -> part.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .orEmpty()
            .trim()

    private companion object {
        val terminalBatchStates = setOf("JOB_STATE_SUCCEEDED", "JOB_STATE_FAILED", "JOB_STATE_CANCELLED", "JOB_STATE_EXPIRED")
        val failedBatchStates = setOf("JOB_STATE_FAILED", "JOB_STATE_CANCELLED", "JOB_STATE_EXPIRED")
    }
}

private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

data class BatchTextRequest(
    val key: String,
    val prompt: String,
    val systemPrompt: String? = null
)
