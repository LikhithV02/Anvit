package com.anvit.localai.retrieval

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtsQueryBuilderTest {
    @Test
    fun buildsBroadPrefixQueryForNaturalLanguage() {
        val query = FtsQueryBuilder.build(
            "What was Stock-in-Trade and Operating margin for O2C in Q4 FY26?"
        )

        assertTrue(query.contains("stock*"))
        assertTrue(query.contains("trade*"))
        assertTrue(query.contains("operating*"))
        assertTrue(query.contains("margin*"))
        assertTrue(query.contains("o2c*"))
        assertTrue(query.contains("q4*"))
        assertTrue(query.contains(" OR "))
        assertFalse(query.contains(" what*"))
    }
}
