package com.sage.localai.agentic

import android.util.Log
import com.sage.localai.inference.GemmaInferenceService

/**
 * Classifies user queries into routing categories for collection-scoped RAG.
 * The DIRECT (no-retrieval) path is handled upstream in ChatViewModel when the user
 * has selected no collection — by the time the orchestrator is called a collection is
 * always active, so every query must go through at least one retrieval pass.
 *
 * Routes: SINGLE_SHOT (one retrieval) | AGENTIC (full decompose/requery loop)
 */
enum class QueryRoute { SINGLE_SHOT, AGENTIC }

class QueryRouter(private val inferenceService: GemmaInferenceService) {

    companion object {
        private const val TAG = "QueryRouter"

        private val SYSTEM_PROMPT = """
You are a query classifier for a document-search system. The user has already chosen a
document collection, so every query should be answered using that collection.
Classify queries into exactly one category:

SINGLE_SHOT - Factual, conversational, or simple questions that need at most ONE focused retrieval.
Examples: "What does the document say about X?", "Summarize the main points",
          "Hello", "What is 2+2?", "Find the definition of Y"

AGENTIC - Complex multi-part questions requiring multiple retrievals, comparison, analysis, or synthesis.
Examples: "Compare X and Y across the documents", "What are all the steps for...",
          "Analyze the relationship between A and B"

Reply with ONLY ONE WORD: SINGLE_SHOT or AGENTIC.
        """.trimIndent()
    }

    suspend fun route(query: String, hasDocuments: Boolean): QueryRoute {
        // No documents in the selected collection — fall back to SINGLE_SHOT so the
        // orchestrator still runs through the system prompt and responds in context.
        if (!hasDocuments) return QueryRoute.SINGLE_SHOT

        return try {
            val response = inferenceService.generateResponse(
                prompt = "Query: \"$query\"\n\nClassify this query:",
                systemPrompt = SYSTEM_PROMPT
            ).trim().uppercase()

            when {
                // DIRECT is no longer a valid route here — treat conversational queries as
                // SINGLE_SHOT so they still benefit from any relevant collection context.
                response.contains("AGENTIC") -> QueryRoute.AGENTIC
                else -> QueryRoute.SINGLE_SHOT
            }.also { Log.d(TAG, "Routed '$query' -> $it (raw: $response)") }
        } catch (e: Exception) {
            Log.e(TAG, "Routing failed, defaulting to SINGLE_SHOT: ${e.message}")
            QueryRoute.SINGLE_SHOT
        }
    }
}
