package com.anvit.localai.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CactusOptionsTest {

    @Test
    fun optionsAlwaysDisableCloudAndTelemetry() {
        val json = Json.parseToJsonElement(
            CactusInferenceOptions(
                temperature = 0.7f,
                topP = 0.9f,
                topK = 32,
                maxTokens = 512,
                stopSequences = listOf("<turn|>")
            ).toJson()
        ).jsonObject

        assertFalse(json.getValue("auto_handoff").jsonPrimitive.boolean)
        assertFalse(json.getValue("telemetry_enabled").jsonPrimitive.boolean)
        assertEquals("512", json.getValue("max_tokens").jsonPrimitive.content)
        assertEquals("<turn|>", json.getValue("stop_sequences").jsonArray.first().jsonPrimitive.content)
    }

    @Test
    fun messagesJsonUsesOpenAiRolesAndIncludesImageWhenProvided() {
        val messages = Json.parseToJsonElement(
            buildCactusMessagesJson(
                systemPrompt = "System",
                userPrompt = "Describe this",
                imagePath = "/tmp/image.png"
            )
        ).jsonArray

        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("System", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertTrue(messages[1].jsonObject.getValue("content").jsonPrimitive.content.contains("/tmp/image.png"))
    }

    @Test
    fun messagesJsonCanRequestGemmaThinkingInSystemPrompt() {
        val messages = Json.parseToJsonElement(
            buildCactusMessagesJson(
                systemPrompt = "System",
                userPrompt = "Question",
                requestThinking = true
            )
        ).jsonArray

        assertEquals("<|think|>\nSystem", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun thinkingRequestCreatesSystemPromptWhenNoneExists() {
        val messages = Json.parseToJsonElement(
            buildCactusMessagesJson(
                systemPrompt = null,
                userPrompt = "Question",
                requestThinking = true
            )
        ).jsonArray

        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("<|think|>", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject.getValue("role").jsonPrimitive.content)
    }
}
