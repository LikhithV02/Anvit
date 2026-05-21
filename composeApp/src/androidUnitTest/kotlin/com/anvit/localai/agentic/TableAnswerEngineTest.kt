package com.anvit.localai.agentic

import com.anvit.localai.retrieval.RetrievedChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TableAnswerEngineTest {
    @Test
    fun answersDirectTableLookupWithExactFormatting() {
        val content = "| Item | Col 2 | Col 3 | Col 4 |\n|---|---|---|---|\n| Progress and Stock-in-Trade | (81-1) | (8,421) | (5 |"
        val result = TableAnswerEngine.answer(
            query = "What did Progress and Stock-in-Trade report in the expenses table?",
            chunks = listOf(chunk(content))
        )

        assertEquals(TableAnswerConfidence.HIGH, result.confidence)
        assertNotNull(result.answer)
        assertTrue(result.answer.contains("(81-1)"))
        assertTrue(result.answer.contains("(8,421)"))
        assertTrue(result.answer.contains("(5"))
    }

    @Test
    fun returnsTableFactsForPartialTableQuestion() {
        val content = "| Item | Amount |\n|---|---|\n| Employee Benefits Expense | 5,100 |\n| Finance Costs | 2,300 |"
        val result = TableAnswerEngine.answer(
            query = "What should I know about the expenses table?",
            chunks = listOf(chunk(content))
        )

        assertEquals(TableAnswerConfidence.MEDIUM, result.confidence)
        assertTrue(result.facts.contains("Employee Benefits Expense"))
        assertTrue(result.answer == null)
    }

    @Test
    fun calculatesSumOnlyFromVisibleCells() {
        val content = "| Item | Amount |\n|---|---|\n| Employee Benefits Expense | 5100 |\n| Finance Costs | 2300 |"
        val result = TableAnswerEngine.answer(
            query = "What is the combined total of Employee Benefits Expense and Finance Costs?",
            chunks = listOf(chunk(content))
        )

        assertEquals(TableAnswerConfidence.HIGH, result.confidence)
        assertTrue(result.answer.orEmpty().contains("7400"))
    }

    private fun chunk(content: String) = RetrievedChunk(
        chunkId = "c1",
        docId = "d1",
        fileName = "financial.pdf",
        chunkIndex = 0,
        content = content,
        score = 1f,
        vectorScore = 1f,
        bm25Rank = 1
    )
}
