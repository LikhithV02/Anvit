package com.anvit.localai.inference

import android.content.Context
// Imports below are used by the commented-out system tools — uncomment when enabling them
// import android.content.Intent
// import android.net.Uri
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.anvit.localai.retrieval.HybridRetriever
import kotlinx.coroutines.runBlocking
// import kotlinx.coroutines.Dispatchers
// import java.net.URL
// import java.net.URLEncoder
// import java.security.MessageDigest

/**
 * Combined agent toolset for Anvit.
 *
 * Provides two categories of tools in a single [ToolSet]:
 *
 * 1. RAG document tools (active) — search the user's uploaded PDF knowledge base.
 *    The active [collectionId] is set by AgenticRagOrchestrator before each
 *    generation so searches are automatically scoped to the selected collection.
 *
 * 2. System / utility tools (commented out, ready to enable) — Wikipedia lookup,
 *    hash calculation, and device intents (email, SMS, map).
 *    To enable: uncomment the imports above and the tool methods below, then
 *    add the tool names back into AGENT_SYSTEM_PROMPT.
 */
class RagAgentTools(
    private val retriever: HybridRetriever,
    private val context: Context
) : ToolSet {

    /** Set this before every generateStream call that uses tools. */
    @Volatile var collectionId: String? = null

    companion object {
        private const val TAG = "RagAgentTools"

        val AGENT_SYSTEM_PROMPT = """
You are Sage, an intelligent document assistant with agentic RAG capabilities.
You have access to a knowledge base of user-uploaded PDF documents.

Available tools:
- search_documents: Search the document knowledge base semantically and lexically
- get_document_section: Retrieve a specific section from a document by keyword

RULES:
1. For any question that might be answered by the documents, ALWAYS call search_documents first.
2. Use tools silently — do not narrate that you are calling tools.
3. If initial search results are insufficient, call search_documents again with a rephrased query.
4. Synthesize retrieved context into a clear, accurate answer.
5. If the documents do not contain relevant information, say so clearly.
6. Cite the source document name when answering from retrieved content.
        """.trimIndent()
    }

    // ── RAG document tools ────────────────────────────────────────────────────

    @Tool(description = "Search the PDF document knowledge base for information relevant to a query. Returns the most relevant text passages.")
    fun searchDocuments(
        @ToolParam(description = "The search query — be specific and use key terms from what you're looking for.") query: String,
        @ToolParam(description = "Maximum number of results to return (1-10, default 5).") maxResults: Int
    ): Map<String, Any> {
        return runBlocking {
            try {
                val safeMax = maxResults.coerceIn(1, 10)
                val results = retriever.retrieve(query, safeMax, collectionId)
                if (results.isEmpty()) {
                    mapOf("status" to "no_results", "message" to "No relevant documents found for: $query")
                } else {
                    val passages = results.mapIndexed { i, chunk ->
                        mapOf(
                            "index"           to i + 1,
                            "source"          to chunk.fileName,
                            "relevance_score" to "%.3f".format(chunk.score),
                            "content"         to chunk.content
                        )
                    }
                    mapOf("status" to "success", "result_count" to results.size, "passages" to passages)
                }
            } catch (e: Exception) {
                Log.e(TAG, "searchDocuments failed: ${e.message}", e)
                mapOf("status" to "error", "message" to "Search failed: ${e.message}")
            }
        }
    }

    @Tool(description = "Retrieve a specific section from documents by searching for a keyword or phrase. Useful for finding definitions, tables, or named sections.")
    fun getDocumentSection(
        @ToolParam(description = "Specific keyword, phrase, or section name to look up.") keyword: String
    ): Map<String, Any> {
        return runBlocking {
            try {
                val results = retriever.retrieveLexical(keyword, maxResults = 3, collectionId = collectionId)
                if (results.isEmpty()) {
                    mapOf("status" to "not_found", "message" to "No section found matching: $keyword")
                } else {
                    val sections = results.map { chunk ->
                        mapOf("source" to chunk.fileName, "content" to chunk.content)
                    }
                    mapOf("status" to "success", "sections" to sections)
                }
            } catch (e: Exception) {
                Log.e(TAG, "getDocumentSection failed: ${e.message}", e)
                mapOf("status" to "error", "message" to "Section lookup failed: ${e.message}")
            }
        }
    }

    // ── System / utility tools (commented out — uncomment to enable) ──────────

    // @Tool(description = "Query a summary from Wikipedia for a given topic. Use this for factual questions about people, places, events, science, history, or current affairs.")
    // fun queryWikipedia(
    //     @ToolParam(description = "Primary topic to look up (e.g., 'Albert Einstein', 'Great Wall of China'). Extract only the key entity — remove question words and action verbs.") topic: String,
    //     @ToolParam(description = "2-letter language code matching the user's language (e.g., 'en', 'es', 'fr', 'de', 'ja', 'hi'). Default: 'en'.") lang: String
    // ): Map<String, String> {
    //     return runBlocking(Dispatchers.IO) {
    //         try {
    //             val langCode = lang.trim().ifEmpty { "en" }
    //             val encoded = URLEncoder.encode(topic.trim(), "UTF-8")
    //             val urlStr = "https://$langCode.wikipedia.org/api/rest_v1/page/summary/$encoded"
    //             val conn = URL(urlStr).openConnection()
    //             conn.connectTimeout = 6000
    //             conn.readTimeout = 6000
    //             val raw = conn.getInputStream().bufferedReader().use { it.readText() }
    //             val ext = Regex(""""extract"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    //                 .find(raw)?.groupValues?.get(1)
    //                 ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim()
    //             if (!ext.isNullOrBlank()) mapOf("result" to ext, "status" to "succeeded")
    //             else mapOf("error" to "No Wikipedia article found for '$topic'.", "status" to "failed")
    //         } catch (e: Exception) {
    //             Log.w(TAG, "Wikipedia lookup failed for '$topic': ${e.message}")
    //             mapOf("error" to "Wikipedia lookup failed: ${e.message}", "status" to "failed")
    //         }
    //     }
    // }

    // @Tool(description = "Calculate the cryptographic hash of a piece of text.")
    // fun calculateHash(
    //     @ToolParam(description = "The text to hash.") text: String,
    //     @ToolParam(description = "Hash algorithm: MD5, SHA-1, SHA-256 (default), or SHA-512.") algorithm: String
    // ): Map<String, String> {
    //     return try {
    //         val algo = when (algorithm.trim().uppercase()) {
    //             "SHA1", "SHA-1"     -> "SHA-1"
    //             "SHA512", "SHA-512" -> "SHA-512"
    //             "MD5"               -> "MD5"
    //             else                -> "SHA-256"
    //         }
    //         val hash = MessageDigest.getInstance(algo)
    //             .digest(text.toByteArray(Charsets.UTF_8))
    //             .joinToString("") { "%02x".format(it) }
    //         mapOf("result" to hash, "algorithm" to algo, "status" to "succeeded")
    //     } catch (e: Exception) {
    //         mapOf("error" to "Hash calculation failed: ${e.message}", "status" to "failed")
    //     }
    // }

    // @Tool(description = "Send an email using the device's email app. Opens a pre-filled compose window.")
    // fun sendEmail(
    //     @ToolParam(description = "Recipient email address.") email: String,
    //     @ToolParam(description = "Subject line of the email.") subject: String,
    //     @ToolParam(description = "Body text of the email.") body: String
    // ): Map<String, String> {
    //     return try {
    //         val intent = Intent(Intent.ACTION_SEND).apply {
    //             data = Uri.parse("mailto:")
    //             type = "text/plain"
    //             putExtra(Intent.EXTRA_EMAIL, arrayOf(email.trim()))
    //             putExtra(Intent.EXTRA_SUBJECT, subject)
    //             putExtra(Intent.EXTRA_TEXT, body)
    //             addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    //         }
    //         context.startActivity(intent)
    //         mapOf("result" to "Email app opened. To: $email, Subject: $subject", "status" to "succeeded")
    //     } catch (e: Exception) {
    //         Log.w(TAG, "sendEmail failed: ${e.message}")
    //         mapOf("error" to "Could not open email app: ${e.message}", "status" to "failed")
    //     }
    // }

    // @Tool(description = "Send an SMS text message using the device's messaging app. Opens a pre-filled compose window.")
    // fun sendSms(
    //     @ToolParam(description = "Recipient phone number (digits only, e.g., '14155552671').") phoneNumber: String,
    //     @ToolParam(description = "Body text of the SMS message.") body: String
    // ): Map<String, String> {
    //     return try {
    //         val uri = Uri.parse("smsto:${phoneNumber.trim()}")
    //         val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
    //             putExtra("sms_body", body)
    //             addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    //         }
    //         context.startActivity(intent)
    //         mapOf("result" to "SMS app opened. To: $phoneNumber", "status" to "succeeded")
    //     } catch (e: Exception) {
    //         Log.w(TAG, "sendSms failed: ${e.message}")
    //         mapOf("error" to "Could not open SMS app: ${e.message}", "status" to "failed")
    //     }
    // }

    // @Tool(description = "Show a location on the device's map app (Google Maps or any installed map).")
    // fun openMap(
    //     @ToolParam(description = "Location to display (e.g., 'Eiffel Tower, Paris' or coordinates '37.42,-122.08').") location: String
    // ): Map<String, String> {
    //     return try {
    //         val encoded = URLEncoder.encode(location.trim(), "UTF-8")
    //         val uri = Uri.parse("geo:0,0?q=$encoded")
    //         val intent = Intent(Intent.ACTION_VIEW, uri).apply {
    //             addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    //         }
    //         context.startActivity(intent)
    //         mapOf("result" to "Map opened for '$location'.", "status" to "succeeded")
    //     } catch (e: Exception) {
    //         Log.w(TAG, "openMap failed: ${e.message}")
    //         mapOf("error" to "Could not open map app: ${e.message}", "status" to "failed")
    //     }
    // }
}
