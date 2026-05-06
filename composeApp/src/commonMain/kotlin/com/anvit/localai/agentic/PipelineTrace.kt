package com.anvit.localai.agentic

import com.anvit.localai.retrieval.RetrievedChunk
import kotlinx.serialization.Serializable

@Serializable
data class TraceChunk(
    val chunkId: String,
    val docId: String,
    val fileName: String,
    val chunkIndex: Int,
    val score: Float,
    val vectorScore: Float,
    val bm25Rank: Int,
    val lexicalScore: Float = 0f,
    val retrievalSource: String = "unknown",
    val ftsQuery: String? = null,
    val groupId: String? = null,
    val contentHash: String = "",
    val contentPreview: String = ""
) {
    companion object {
        fun from(chunk: RetrievedChunk): TraceChunk = TraceChunk(
            chunkId = chunk.chunkId,
            docId = chunk.docId,
            fileName = chunk.fileName,
            chunkIndex = chunk.chunkIndex,
            score = chunk.score,
            vectorScore = chunk.vectorScore,
            bm25Rank = chunk.bm25Rank,
            lexicalScore = chunk.lexicalScore,
            retrievalSource = chunk.retrievalSource,
            ftsQuery = chunk.ftsQuery,
            groupId = chunk.groupId,
            contentHash = stableContentHash(chunk.content),
            contentPreview = chunk.content.take(600)
        )

        private fun stableContentHash(content: String): String {
            var hash = -3750763034362895579L
            val prime = 1099511628211L
            content.trim().lowercase().encodeToByteArray().forEach { byte ->
                hash = hash xor (byte.toLong() and 0xffL)
                hash *= prime
            }
            return hash.toString(16)
        }
    }
}

@Serializable
data class PipelineTrace(
    val queryId: String,
    val userQuery: String,
    val route: String,
    val subQueries: List<String> = emptyList(),
    val retrievedChunkIds: List<String> = emptyList(),
    val dedupedChunkIds: List<String> = emptyList(),
    val relevanceAction: String = "",
    val requeryAttempts: Int = 0,
    val supplementChunkIds: List<String> = emptyList(),
    val reducedChunkIds: List<String> = emptyList(),
    val finalChunkIds: List<String> = emptyList(),
    val retrievedChunks: List<TraceChunk> = emptyList(),
    val finalChunks: List<TraceChunk> = emptyList(),
    val vectorResultCount: Int = 0,
    val lexicalResultCount: Int = 0,
    val ftsQueries: List<String> = emptyList(),
    val retrievalSources: Map<String, Int> = emptyMap(),
    val selfCritiqueTriggered: Boolean = false,
    val gapQuery: String? = null,
    val gapChunkIds: List<String> = emptyList(),
    val generatedAnswer: String = "",
    val latencyMs: Map<String, Long> = emptyMap(),
    val totalLatencyMs: Long = 0L
)

internal class PipelineTraceRecorder(
    private val queryId: String,
    private val userQuery: String
) {
    var route: String = QueryRoute.SINGLE_SHOT.name
    val subQueries: MutableList<String> = mutableListOf()
    val retrievedChunks: MutableList<RetrievedChunk> = mutableListOf()
    val dedupedChunks: MutableList<RetrievedChunk> = mutableListOf()
    var relevanceAction: String = ""
    var requeryAttempts: Int = 0
    val supplementChunks: MutableList<RetrievedChunk> = mutableListOf()
    val reducedChunks: MutableList<RetrievedChunk> = mutableListOf()
    val finalChunks: MutableList<RetrievedChunk> = mutableListOf()
    var selfCritiqueTriggered: Boolean = false
    var gapQuery: String? = null
    val gapChunks: MutableList<RetrievedChunk> = mutableListOf()
    var generatedAnswer: String = ""
    val latencyMs: MutableMap<String, Long> = linkedMapOf()
    var totalLatencyMs: Long = 0L

    fun snapshot(): PipelineTrace = PipelineTrace(
        queryId = queryId,
        userQuery = userQuery,
        route = route,
        subQueries = subQueries.toList(),
        retrievedChunkIds = retrievedChunks.map { it.chunkId },
        dedupedChunkIds = dedupedChunks.map { it.chunkId },
        relevanceAction = relevanceAction,
        requeryAttempts = requeryAttempts,
        supplementChunkIds = supplementChunks.map { it.chunkId },
        reducedChunkIds = reducedChunks.map { it.chunkId },
        finalChunkIds = finalChunks.map { it.chunkId },
        retrievedChunks = retrievedChunks.map { TraceChunk.from(it) },
        finalChunks = finalChunks.map { TraceChunk.from(it) },
        vectorResultCount = retrievedChunks.count { it.vectorScore > 0f },
        lexicalResultCount = retrievedChunks.count { it.bm25Rank > 0 },
        ftsQueries = retrievedChunks.mapNotNull { it.ftsQuery }.distinct(),
        retrievalSources = retrievedChunks.groupingBy { it.retrievalSource }.eachCount(),
        selfCritiqueTriggered = selfCritiqueTriggered,
        gapQuery = gapQuery,
        gapChunkIds = gapChunks.map { it.chunkId },
        generatedAnswer = generatedAnswer,
        latencyMs = latencyMs.toMap(),
        totalLatencyMs = totalLatencyMs
    )
}
