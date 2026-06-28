package com.anvit.localai.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingMarkerParserTest {

    @Test
    fun parsesCactusChannelThoughtMarkersAcrossTokenBoundaries() {
        val parser = ThinkingMarkerStreamParser()

        val output = listOf(
            "Before <|channel>",
            "thought\nworking",
            "<channel|> after"
        ).flatMap { parser.accept(it) } + parser.finish()

        assertEquals(
            listOf(
                "Before ",
                InferenceService.SENTINEL_THINK,
                "working",
                InferenceService.SENTINEL_ENDTHINK,
                " after"
            ),
            output
        )
    }

    @Test
    fun parsesXmlThinkMarkersAcrossTokenBoundaries() {
        val parser = ThinkingMarkerStreamParser()

        val output = listOf(
            "Before <thi",
            "nk>\nworking",
            "</think> after"
        ).flatMap { parser.accept(it) } + parser.finish()

        assertEquals(
            listOf(
                "Before ",
                InferenceService.SENTINEL_THINK,
                "working",
                InferenceService.SENTINEL_ENDTHINK,
                " after"
            ),
            output
        )
    }

    @Test
    fun stripsSupportedThinkingMarkersFromCompletedText() {
        assertEquals(
            "Before after",
            stripThinkingMarkersFromText("Before <think>hidden</think> after")
        )
        assertEquals(
            "Before after",
            stripThinkingMarkersFromText("Before <|channel>thought hidden<channel|> after")
        )
    }
}
