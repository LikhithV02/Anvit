package com.anvit.localai.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmParameterPresentationTest {

    @Test
    fun androidDefaultsMatchLiteRtGenerationDefaults() {
        val defaults = llmParameterDefaults(isIos = false)

        assertEquals(1.0f, defaults.temperature, 0.001f)
        assertEquals(40, defaults.topK)
        assertEquals(8192, defaults.contextWindow)
        assertEquals(4000, defaults.maxOutputTokens)
        assertEquals(5, defaults.maxRetrievalChunks)
        assertEquals("hybrid", defaults.retrievalMode)
    }

    @Test
    fun iosDefaultsMatchCactusGenerationDefaults() {
        val defaults = llmParameterDefaults(isIos = true)

        assertEquals(0.6f, defaults.temperature, 0.001f)
        assertEquals(40, defaults.topK)
        assertEquals(8192, defaults.contextWindow)
        assertEquals(4000, defaults.maxOutputTokens)
        assertEquals(5, defaults.maxRetrievalChunks)
        assertEquals("bm25", defaults.retrievalMode)
    }

    @Test
    fun iosContextWindowSettingIsCappedToCurrentCactusRuntimeLimit() {
        assertEquals(8192, contextWindowSettingMax(isIos = true, modelContextWindow = 128000))
        assertEquals(4096, contextWindowSettingMax(isIos = true, modelContextWindow = 4096))
        assertEquals(128000, contextWindowSettingMax(isIos = false, modelContextWindow = 128000))
    }
}
