package com.anvit.localai.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingModePresentationTest {

    @Test
    fun nextToggleStateFlipsTheCurrentMode() {
        assertEquals(false, nextThinkingMode(enabled = true))
        assertEquals(true, nextThinkingMode(enabled = false))
    }

    @Test
    fun currentLabelReflectsSelectedMode() {
        assertEquals("Think", thinkingModeLabel(enabled = true))
        assertEquals("Think", thinkingModeLabel(enabled = false))
    }
}
