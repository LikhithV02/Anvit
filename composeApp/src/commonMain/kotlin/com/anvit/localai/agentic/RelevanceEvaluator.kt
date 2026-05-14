package com.anvit.localai.agentic

import com.anvit.localai.inference.InferenceService
import com.anvit.localai.retrieval.RetrievedChunk

/**
 * CRAG (Corrective RAG) evaluator: checks if retrieved chunks are relevant to the query.
 * Returns an action: USE | REQUERY | SUPPLEMENT
 */
enum class RelevanceAction { USE, REQUERY, SUPPLEMENT }

class RelevanceEvaluator(private val inferenceService: InferenceService) {

    companion object {
        private const val TAG = "RelevanceEvaluator"

        private val SYSTEM_PROMPT = """
You evaluate document retrieval quality. Given a user query and retrieved text passages, decide:

	USE - The passages contain directly relevant information to answer the query.
	SUPPLEMENT - The passages are partially relevant but more retrieval may help.
	REQUERY - The passages are irrelevant or do not address the query at all.
	
	For table, financial, or multi-part questions, reply USE only if the passages cover every requested metric, entity, row, column, or comparison. If any requested part is missing, reply SUPPLEMENT.

Reply with ONLY ONE WORD: USE, SUPPLEMENT, or REQUERY.
        """.trimIndent()
    }

    suspend fun evaluate(query: String, chunks: List<RetrievedChunk>): RelevanceAction {
        if (chunks.isEmpty()) return RelevanceAction.REQUERY

        return try {
	            val passagesSummary = chunks.take(5).joinToString("\n---\n") {
	                "[${it.fileName}]: ${it.content.take(700)}"
            }
            val prompt = """
Query: "$query"

Retrieved passages:
$passagesSummary

Are these passages relevant to the query?
            """.trimIndent()

            val response = inferenceService.generateResponse(
                prompt = prompt,
                systemPrompt = SYSTEM_PROMPT,
                allowThinking = false
            ).trim().uppercase()
            when {
                response.contains("REQUERY") -> RelevanceAction.REQUERY
                response.contains("SUPPLEMENT") -> RelevanceAction.SUPPLEMENT
                else -> RelevanceAction.USE
            }.also { println("[$TAG] Relevance for '$query' -> $it") }
        } catch (e: Exception) {
            println("[$TAG] Relevance eval failed, defaulting to USE: ${e.message}")
            RelevanceAction.USE
        }
    }
}
