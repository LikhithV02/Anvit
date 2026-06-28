package com.anvit.localai.inference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class CactusInferenceOptions(
    val temperature: Float = 0.6f,
    @SerialName("top_p") val topP: Float = 0.95f,
    @SerialName("top_k") val topK: Int = 40,
    @SerialName("min_p") val minP: Float? = null,
    @SerialName("repetition_penalty") val repetitionPenalty: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int = 4000,
    @SerialName("stop_sequences") val stopSequences: List<String> = emptyList(),
    @SerialName("enable_thinking_if_supported") val enableThinkingIfSupported: Boolean = true
) {
    fun toJson(): String =
        buildJsonObject {
            put("temperature", temperature)
            put("top_p", topP)
            put("top_k", topK)
            minP?.let { put("min_p", it) }
            repetitionPenalty?.let { put("repetition_penalty", it) }
            put("max_tokens", maxTokens)
            put("stop_sequences", buildJsonArray { stopSequences.forEach { add(it) } })
            put("enable_thinking_if_supported", enableThinkingIfSupported)
            put("auto_handoff", false)
            put("telemetry_enabled", false)
        }.toString()
}

fun buildCactusMessagesJson(
    systemPrompt: String?,
    userPrompt: String,
    imagePath: String? = null,
    requestThinking: Boolean = false,
): String =
    buildJsonArray {
        val effectiveSystemPrompt = when {
            requestThinking && !systemPrompt.isNullOrBlank() -> "<|think|>\n$systemPrompt"
            requestThinking -> "<|think|>"
            else -> systemPrompt
        }

        if (!effectiveSystemPrompt.isNullOrBlank()) {
            add(buildJsonObject {
                put("role", "system")
                put("content", effectiveSystemPrompt)
            })
        }
        add(buildJsonObject {
            put("role", "user")
            put(
                "content",
                if (imagePath.isNullOrBlank()) userPrompt else "$userPrompt\n\n[image: $imagePath]"
            )
        })
    }.toString()
