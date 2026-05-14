package com.anvit.localai.agentic

import com.anvit.localai.inference.InferenceService

/**
 * Post-generation self-critique: checks if the response adequately answers the query.
 * Returns a gap query if the response is incomplete, or null if it's sufficient.
 */
class SelfCritiqueLoop(private val inferenceService: InferenceService) {

    companion object {
        private const val TAG = "SelfCritique"

        private val SYSTEM_PROMPT = """
You critically evaluate AI responses to user queries.

If the response fully and accurately answers the query based on the provided context, reply:
SUFFICIENT

	If the response is missing important information or is incomplete, reply with:
	INSUFFICIENT: <one specific follow-up search query to find the missing information>

Example: INSUFFICIENT: What are the specific requirements mentioned in section 3?

	For table, financial, or document-fact answers, every number, entity, and cited fact in the response must appear in the context. If the answer invents or changes a value, reply INSUFFICIENT with a search query for the missing exact value.
	Be strict: only mark SUFFICIENT when the response is complete and all requested fields are supported. Reply ONLY in the format above.
        """.trimIndent()
    }

    /**
     * Returns null if response is sufficient, or a gap query string if more retrieval is needed.
     */
    suspend fun evaluate(userQuery: String, response: String, contextSummary: String): String? {
        return try {
            val prompt = """
User Query: "$userQuery"

	Context used:
	${contextSummary.take(1800)}

	AI Response:
	${response.take(1200)}

Is the response sufficient?
            """.trimIndent()

            val result = inferenceService.generateResponse(
                prompt = prompt,
                systemPrompt = SYSTEM_PROMPT,
                allowThinking = false
            ).trim()
            println("[$TAG] Self-critique result: ${result.take(100)}")

            if (result.startsWith("INSUFFICIENT", ignoreCase = true)) {
                val colonIdx = result.indexOf(':')
                if (colonIdx >= 0) result.substring(colonIdx + 1).trim() else null
            } else {
                null // SUFFICIENT
            }
        } catch (e: Exception) {
            println("[$TAG] Self-critique failed: ${e.message}")
            null
        }
    }
}
