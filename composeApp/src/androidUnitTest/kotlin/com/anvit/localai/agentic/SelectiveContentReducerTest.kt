package com.anvit.localai.agentic

import com.anvit.localai.document.TokenCounter
import com.anvit.localai.retrieval.RetrievedChunk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectiveContentReducerTest {
    @Test
    fun trimsLargeTableButKeepsHeaderAndMatchingRows() {
        val rows = (1..80).joinToString("\n") { index ->
            val segment = if (index == 42) "O2C" else "Other $index"
            "Segment: $segment | Revenue: ${index * 10} | EBITDA: ${index * 2} | Margin: ${index}%"
        }
        val chunk = chunk("Table: Segment, Revenue, EBITDA, Margin\n$rows")

        val reduced = SelectiveContentReducer.reduce(
            query = "What is O2C EBITDA margin?",
            chunks = listOf(chunk),
            maxStructuredTokensPerChunk = 90
        ).single()

        assertTrue(reduced.content.startsWith("Table: Segment"))
        assertTrue(reduced.content.contains("Segment: O2C"))
        assertTrue(TokenCounter.estimate(reduced.content) <= 90)
        assertFalse(reduced.content.contains("Other 80"))
    }

    @Test
    fun trimsLargeListButKeepsMatchingItems() {
        val items = (1..60).joinToString("\n") { index ->
            if (index == 33) "Subscriber additions improved ARPU during the quarter"
            else "Generic list item $index"
        }
        val chunk = chunk("List summary: 60 items. First: Generic list item 1\n$items")

        val reduced = SelectiveContentReducer.reduce(
            query = "What improved ARPU?",
            chunks = listOf(chunk),
            maxStructuredTokensPerChunk = 70
        ).single()

        assertTrue(reduced.content.startsWith("List summary:"))
        assertTrue(reduced.content.contains("ARPU"))
        assertTrue(TokenCounter.estimate(reduced.content) <= 70)
    }

    private fun chunk(content: String) = RetrievedChunk(
        chunkId = "c1",
        docId = "d1",
        fileName = "doc.pdf",
        chunkIndex = 0,
        content = content,
        score = 1f,
        vectorScore = 1f,
        bm25Rank = 1
    )
}
