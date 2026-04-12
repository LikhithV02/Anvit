package com.sage.localai.agentic

import android.util.Log
import com.sage.localai.inference.GemmaInferenceService

/**
 * Post-generation self-critique: checks if the response adequately answers the query.
 * Returns a gap query if the response is incomplete, or null if it's sufficient.
 */
class SelfCritiqueLoop(private val inferenceService: GemmaInferenceService) {

    companion object {
        private const val TAG = "SelfCritique"

        private val SYSTEM_PROMPT = """
You critically evaluate AI responses to user queries.

If the response fully and accurately answers the query based on the provided context, reply:
SUFFICIENT

If the response is missing important information or is incomplete, reply with:
INSUFFICIENT: <one specific follow-up search query to find the missing information>

Example: INSUFFICIENT: What are the specific requirements mentioned in section 3?

Be strict: only mark INSUFFICIENT if there is clearly missing factual content. Reply ONLY in the format above.
        """.trimIndent()
    }

    /**
     * Returns null if response is sufficient, or a gap query string if more retrieval is needed.
     */
    suspend fun evaluate(userQuery: String, response: String, contextSummary: String): String? {
        return try {
            val prompt = """
User Query: "$userQuery"

Context used: ${contextSummary.take(500)}

AI Response: ${response.take(800)}

Is the response sufficient?
            """.trimIndent()

            val result = inferenceService.generateResponse(prompt, SYSTEM_PROMPT).trim()
            Log.d(TAG, "Self-critique result: ${result.take(100)}")

            if (result.startsWith("INSUFFICIENT", ignoreCase = true)) {
                val colonIdx = result.indexOf(':')
                if (colonIdx >= 0) result.substring(colonIdx + 1).trim() else null
            } else {
                null // SUFFICIENT
            }
        } catch (e: Exception) {
            Log.e(TAG, "Self-critique failed: ${e.message}")
            null
        }
    }
}
