package com.anvit.localai.agentic

import com.anvit.localai.inference.InferenceService

/**
 * Decomposes complex queries into sub-queries for multi-hop retrieval.
 */
class QueryDecomposer(private val inferenceService: InferenceService) {

    companion object {
        private const val TAG = "QueryDecomposer"
        private const val MAX_SUB_QUERIES = 4

        private val SYSTEM_PROMPT = """
You decompose complex questions into simpler sub-questions for document retrieval.
Output ONLY a JSON array of sub-question strings. No explanation, no markdown.
Example output: ["sub-question 1", "sub-question 2", "sub-question 3"]
Keep sub-questions concise and focused. Maximum $MAX_SUB_QUERIES sub-questions.
Preserve exact company names, segment names, table labels, metrics, quarters, years, and numbers from the original query.
For table questions, create focused sub-queries that include the exact row/column labels.
If the query is simple, output a single-element array: ["original query"]
        """.trimIndent()
    }

    suspend fun decompose(query: String): List<String> {
        return try {
            val response = inferenceService.generateResponse(
                prompt = "Decompose this question into sub-questions for document retrieval:\n\"$query\"",
                systemPrompt = SYSTEM_PROMPT
            ).trim()

            includeOriginalFallback(parseSubQueries(response, query), query)
        } catch (e: Exception) {
            println("[$TAG] Decomposition failed, using original query: ${e.message}")
            listOf(query)
        }
    }

    private fun includeOriginalFallback(items: List<String>, original: String): List<String> {
        val normalizedOriginal = original.trim()
        val deduped = items
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        return if (deduped.any { it.equals(normalizedOriginal, ignoreCase = true) }) {
            deduped.take(MAX_SUB_QUERIES)
        } else {
            (deduped.take(MAX_SUB_QUERIES - 1) + normalizedOriginal).filter { it.isNotBlank() }
        }
    }

    private fun parseSubQueries(response: String, fallback: String): List<String> {
        return try {
            // Extract JSON array
            val jsonStart = response.indexOf('[')
            val jsonEnd = response.lastIndexOf(']')
            if (jsonStart < 0 || jsonEnd < 0) return listOf(fallback)
            val jsonStr = response.substring(jsonStart, jsonEnd + 1)

            // Simple string extraction from JSON array (avoid heavy JSON dependency)
            val items = mutableListOf<String>()
            val pattern = Regex(""""([^"]+)"""")
            pattern.findAll(jsonStr).forEach { items.add(it.groupValues[1]) }

            if (items.isEmpty()) listOf(fallback)
            else items.take(MAX_SUB_QUERIES).also {
                println("[$TAG] Decomposed into ${it.size} sub-queries: $it")
            }
        } catch (e: Exception) {
            println("[$TAG] Failed to parse sub-queries: ${e.message}")
            listOf(fallback)
        }
    }
}
