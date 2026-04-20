package com.anvit.localai.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.anvit.localai.retrieval.HybridRetriever
import kotlinx.coroutines.runBlocking

/**
 * Android-only agent toolset for Anvit.
 *
 * Wired into [GemmaInferenceService] by the Android Koin module.
 * The active [collectionId] is set by AgenticRagOrchestrator before each generation.
 */
class RagAgentTools(
    private val retriever: HybridRetriever,
    private val context: Context
) : ToolSet {

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
}
