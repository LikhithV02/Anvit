package com.sage.localai.agentic

import android.util.Log
import com.sage.localai.inference.GemmaInferenceService

/**
 * Decomposes complex queries into sub-queries for multi-hop retrieval.
 */
class QueryDecomposer(private val inferenceService: GemmaInferenceService) {

    companion object {
        private const val TAG = "QueryDecomposer"
        private const val MAX_SUB_QUERIES = 4

        private val SYSTEM_PROMPT = """
You decompose complex questions into simpler sub-questions for document retrieval.
Output ONLY a JSON array of sub-question strings. No explanation, no markdown.
Example output: ["sub-question 1", "sub-question 2", "sub-question 3"]
Keep sub-questions concise and focused. Maximum $MAX_SUB_QUERIES sub-questions.
If the query is simple, output a single-element array: ["original query"]
        """.trimIndent()
    }

    suspend fun decompose(query: String): List<String> {
        return try {
            val response = inferenceService.generateResponse(
                prompt = "Decompose this question into sub-questions for document retrieval:\n\"$query\"",
                systemPrompt = SYSTEM_PROMPT
            ).trim()

            parseSubQueries(response, query)
        } catch (e: Exception) {
            Log.e(TAG, "Decomposition failed, using original query: ${e.message}")
            listOf(query)
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
                Log.d(TAG, "Decomposed into ${it.size} sub-queries: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sub-queries: ${e.message}")
            listOf(fallback)
        }
    }
}
