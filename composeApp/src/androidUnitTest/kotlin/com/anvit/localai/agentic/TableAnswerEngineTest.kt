package com.anvit.localai.agentic

import com.anvit.localai.retrieval.RetrievedChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TableAnswerEngineTest {
    @Test
    fun answersDirectTableLookupWithExactFormatting() {
        val result = TableAnswerEngine.answer(
            query = "What did Progress and Stock-in-Trade report in the expenses table?",
            chunks = listOf(chunk("Table: Item, Col 2, Col 3, ,014)\nItem: Progress and Stock-in-Trade | Col 2: (81-1) | Col 3: (8,421) | ,014): (5"))
        )

        assertEquals(TableAnswerConfidence.HIGH, result.confidence)
        assertNotNull(result.answer)
        assertTrue(result.answer.contains("(81-1)"))
        assertTrue(result.answer.contains("(8,421)"))
        assertTrue(result.answer.contains("(5"))
    }

    @Test
    fun returnsTableFactsForPartialTableQuestion() {
        val result = TableAnswerEngine.answer(
            query = "What should I know about the expenses table?",
            chunks = listOf(chunk("Table: Item, Amount\nItem: Employee Benefits Expense | Amount: 5,100\nItem: Finance Costs | Amount: 2,300"))
        )

        assertEquals(TableAnswerConfidence.MEDIUM, result.confidence)
        assertTrue(result.facts.contains("Employee Benefits Expense"))
        assertTrue(result.answer == null)
    }

    @Test
    fun calculatesSumOnlyFromVisibleCells() {
        val result = TableAnswerEngine.answer(
            query = "What is the combined total of Employee Benefits Expense and Finance Costs?",
            chunks = listOf(chunk("Table: Item, Amount\nItem: Employee Benefits Expense | Amount: 5100\nItem: Finance Costs | Amount: 2300"))
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
