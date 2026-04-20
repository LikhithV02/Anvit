package com.anvit.localai.agentic

import com.anvit.localai.inference.InferenceService

enum class QueryRoute { SINGLE_SHOT, AGENTIC }

class QueryRouter(private val inferenceService: InferenceService) {
    companion object {
        private val SYSTEM_PROMPT = """
You are a query classifier for a document-search system. The user has already chosen a document collection, so every query should be answered using that collection.
Classify queries into exactly one category:
SINGLE_SHOT - Factual, conversational, or simple questions that need at most ONE focused retrieval.
AGENTIC - Complex multi-part questions requiring multiple retrievals, comparison, analysis, or synthesis.
Reply with ONLY ONE WORD: SINGLE_SHOT or AGENTIC.
        """.trimIndent()
    }

    suspend fun route(query: String, hasDocuments: Boolean): QueryRoute {
        if (!hasDocuments) return QueryRoute.SINGLE_SHOT
        return try {
            val response = inferenceService.generateResponse(
                prompt = "Query: \"$query\"\n\nClassify this query:",
                systemPrompt = SYSTEM_PROMPT
            ).trim().uppercase()
            (if (response.contains("AGENTIC")) QueryRoute.AGENTIC else QueryRoute.SINGLE_SHOT)
                .also { println("[QueryRouter] Route: $it") }
        } catch (e: Exception) {
            println("[QueryRouter] Routing failed, defaulting to SINGLE_SHOT: ${e.message}")
            QueryRoute.SINGLE_SHOT
        }
    }
}
