package com.anvit.localai.agentic

import android.util.Log
import com.anvit.localai.data.db.DocumentDao
import com.anvit.localai.inference.GemmaInferenceService
import com.anvit.localai.retrieval.HybridRetriever
import com.anvit.localai.retrieval.RetrievedChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class AgentStep(val type: String, val description: String)

data class AgenticResult(
    val answerFlow: Flow<String>,
    val steps: List<AgentStep>
)

class AgenticRagOrchestrator(
    private val inferenceService: GemmaInferenceService,
    private val hybridRetriever: HybridRetriever,
    private val documentDao: DocumentDao
) {
    companion object {
        private const val TAG = "AgenticOrchestrator"
        private const val MAX_CRITIQUE_ITERATIONS = 1
        private const val MAX_REQUERY_ATTEMPTS = 2
    }

    private val router       = QueryRouter(inferenceService)
    private val decomposer   = QueryDecomposer(inferenceService)
    private val evaluator    = RelevanceEvaluator(inferenceService)
    private val contentReducer = SelectiveContentReducer
    private val critiqueLoop = SelfCritiqueLoop(inferenceService)

    suspend fun process(
        userQuery: String,
        conversationHistory: String,
        enableAgenticRag: Boolean,
        maxChunks: Int,
        enableSelfCritique: Boolean,
        useAgentTools: Boolean,
        collectionId: String? = null,
        imagePath: String? = null,
        audioBytes: ByteArray? = null,
        onStep: suspend (AgentStep) -> Unit = {},
        onSources: suspend (List<RetrievedChunk>) -> Unit = {}
    ): AgenticResult {
        val steps = mutableListOf<AgentStep>()

        val emitStep: suspend (String, String) -> Unit = { type, description ->
            val step = AgentStep(type, description)
            steps.add(step)
            onStep(step)
        }

        val totalChunks = if (collectionId.isNullOrEmpty() || collectionId == "all")
            documentDao.getTotalChunkCount()
        else
            documentDao.getChunkCountForCollection(collectionId)

        val hasDocuments = totalChunks > 0

        // Scope agent tool searches to the active collection before any generation call
        inferenceService.setActiveCollection(collectionId)

        // When agenticRag is disabled or the collection has no documents, fall back to
        // SINGLE_SHOT — it retrieves 0 chunks and generates with an empty context, but
        // still runs through the unified system-prompt path rather than bypassing RAG.
        val route = if (enableAgenticRag && hasDocuments) {
            router.route(userQuery, hasDocuments).also {
                emitStep("route", when (it) {
                    QueryRoute.AGENTIC     -> "This looks like a complex question — I'll search in multiple steps"
                    QueryRoute.SINGLE_SHOT -> "Searching your documents for a direct answer"
                })
                Log.d(TAG, "Route: $it")
            }
        } else {
            QueryRoute.SINGLE_SHOT
        }

        return when (route) {
            QueryRoute.SINGLE_SHOT -> {
                emitStep("retrieve", "Searching your documents...")
                val chunks  = hybridRetriever.retrieve(userQuery, maxChunks, collectionId)
                val reduced = contentReducer.reduce(userQuery, chunks)
                onSources(reduced)
                AgenticResult(
                    answerFlow = inferenceService.generateStream(
                        prompt        = buildPrompt(conversationHistory, userQuery, null),
                        systemPrompt  = buildSystemPrompt(buildContextString(reduced)),
                        useAgentTools = useAgentTools,
                        imagePath     = imagePath,
                        audioBytes    = audioBytes
                    ),
                    steps = steps
                )
            }

            QueryRoute.AGENTIC -> agenticFlow(
                userQuery, conversationHistory, steps, emitStep, onSources, maxChunks,
                enableSelfCritique, useAgentTools, collectionId, imagePath, audioBytes
            )
        }
    }

    private suspend fun agenticFlow(
        userQuery: String,
        conversationHistory: String,
        steps: MutableList<AgentStep>,
        emitStep: suspend (String, String) -> Unit,
        onSources: suspend (List<RetrievedChunk>) -> Unit,
        maxChunks: Int,
        enableSelfCritique: Boolean,
        useAgentTools: Boolean,
        collectionId: String?,
        imagePath: String? = null,
        audioBytes: ByteArray? = null
    ): AgenticResult {
        emitStep("decompose", "Breaking your question into focused search queries")
        val subQueries = decomposer.decompose(userQuery)

        val allChunks = mutableListOf<RetrievedChunk>()
        subQueries.forEach { subQuery ->
            emitStep("retrieve", "Searching for: \"${subQuery.take(60)}\"")
            allChunks.addAll(hybridRetriever.retrieve(subQuery, maxChunks, collectionId))
        }

        var finalChunks = allChunks.distinctBy { it.chunkId }
            .sortedByDescending { it.score }.take(maxChunks)

        var requeryAttempts = 0
        var relevanceAction = evaluator.evaluate(userQuery, finalChunks)

        while (relevanceAction == RelevanceAction.REQUERY && requeryAttempts < MAX_REQUERY_ATTEMPTS) {
            requeryAttempts++
            emitStep("requery", "Results weren't quite right — trying a different search (attempt $requeryAttempts)")
            val rephrased = rephrase(userQuery)
            val newChunks = hybridRetriever.retrieve(rephrased, maxChunks, collectionId)
            if (newChunks.isNotEmpty()) {
                finalChunks    = newChunks
                relevanceAction = evaluator.evaluate(userQuery, finalChunks)
            } else break
        }

        if (relevanceAction == RelevanceAction.SUPPLEMENT) {
            emitStep("supplement", "Gathering a few more relevant passages to fill any gaps")
            val extra = hybridRetriever.retrieve(userQuery, 3, collectionId)
            finalChunks = (finalChunks + extra).distinctBy { it.chunkId }
                .sortedByDescending { it.score }.take(maxChunks)
        }

        emitStep("reduce", "Selecting the most useful passages from what was found")
        val reducedChunks  = contentReducer.reduce(userQuery, finalChunks)
        var allUsedChunks = reducedChunks
        onSources(allUsedChunks)
        val context        = buildContextString(reducedChunks)
        val systemPrompt   = buildSystemPrompt(context)

        emitStep("generate", "Writing your answer using ${reducedChunks.size} relevant passage${if (reducedChunks.size == 1) "" else "s"}")

        val responseFlow = flow<String> {
            val fullResponse = StringBuilder()
            inferenceService.generateStream(
                prompt        = buildPrompt(conversationHistory, userQuery, null),
                systemPrompt  = systemPrompt,
                useAgentTools = useAgentTools,
                imagePath     = imagePath,
                audioBytes    = audioBytes
            ).collect { chunk ->
                fullResponse.append(chunk)
                emit(chunk)
            }

            if (enableSelfCritique && reducedChunks.isNotEmpty()) {
                val contextSummary = reducedChunks.take(3).joinToString("; ") { it.fileName }
                val gapQuery = critiqueLoop.evaluate(userQuery, fullResponse.toString(), contextSummary)
                if (gapQuery != null) {
                    Log.d(TAG, "Self-critique gap found. Re-querying: $gapQuery")
                    emit("\n\n---\n*Refining with additional context...*\n\n")
                    val extraChunks = hybridRetriever.retrieve(gapQuery, 3, collectionId)
                    if (extraChunks.isNotEmpty()) {
                        val extraReduced = contentReducer.reduce(gapQuery, extraChunks)
                        allUsedChunks = (allUsedChunks + extraReduced).distinctBy { it.chunkId }
                        onSources(allUsedChunks)
                        val extraContext = buildContextString(extraReduced)
                        val refinedSystemPrompt = buildSystemPrompt("$context\n\nAdditional context:\n$extraContext")
                        val refinedPrompt = buildPrompt(conversationHistory, userQuery,
                            "Previous answer was incomplete. Use the additional context to complete the answer.")
                        inferenceService.generateStream(refinedPrompt, refinedSystemPrompt, false).collect { emit(it) }
                    }
                }
            }
        }

        return AgenticResult(answerFlow = responseFlow, steps = steps)
    }

    private fun buildContextString(chunks: List<RetrievedChunk>): String {
        if (chunks.isEmpty()) return ""
        return chunks.joinToString("\n\n---\n\n") { chunk ->
            "[Source: ${chunk.fileName}, relevance: ${"%.2f".format(chunk.score)}]\n${chunk.content}"
        }
    }

    private fun buildSystemPrompt(context: String): String {
        return if (context.isBlank()) {
            "You are Anvit, a helpful and accurate AI document assistant. Answer the question directly and concisely."
        } else {
            """
You are Anvit, a helpful and accurate AI document assistant.

Answer the user's question directly and specifically — do not pad with filler, caveats, or repetition.
Be crisp: give the exact answer first, then supporting detail only if it adds value.
Answer ONLY based on the provided document context. If the context does not contain the answer, say so in one sentence.
Always cite the source document name when referencing specific content.

DOCUMENT CONTEXT:
$context
            """.trimIndent()
        }
    }

    private fun buildPrompt(history: String, query: String, note: String?): String {
        val noteStr = if (note != null) "\n[Note: $note]" else ""
        return if (history.isBlank()) "$query$noteStr"
        else "$history\n\nuser: $query$noteStr"
    }

    private suspend fun rephrase(query: String): String {
        return try {
            inferenceService.generateResponse(
                prompt = "Rephrase this search query using different keywords:\n\"$query\"\n\nRephrased:",
                systemPrompt = "You rephrase search queries. Output ONLY the rephrased query, nothing else."
            ).trim().removeSurrounding("\"")
        } catch (e: Exception) { query }
    }
}
