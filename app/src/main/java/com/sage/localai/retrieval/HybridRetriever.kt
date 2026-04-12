package com.sage.localai.retrieval

import android.util.Log
import com.sage.localai.data.db.DocumentDao
import com.sage.localai.data.db.entities.ChunkEntity
import com.sage.localai.data.preferences.SagePreferences
import com.sage.localai.embedding.EmbeddingService
import com.sage.localai.utils.sanitizeForFts
import com.sage.localai.utils.toFloatArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class RetrievedChunk(
    val chunkId: String,
    val docId: String,
    val fileName: String,
    val chunkIndex: Int,
    val content: String,
    val score: Float,       // Combined RRF score
    val vectorScore: Float, // Raw cosine similarity
    val bm25Rank: Int       // BM25 rank (0 = not in BM25 results)
)

class HybridRetriever(
    private val documentDao: DocumentDao,
    private val embeddingService: EmbeddingService
) {
    companion object {
        private const val TAG = "HybridRetriever"
        private const val RRF_K = 60
        private const val VECTOR_THRESHOLD_GEMMA = 0.50f
        private const val VECTOR_THRESHOLD_GECKO = 0.75f
    }

    /**
     * Hybrid retrieval combining vector + BM25 via Reciprocal Rank Fusion.
     * Pass [collectionId] to scope retrieval to a specific collection, or null / "all" for global.
     */
    suspend fun retrieve(
        query: String,
        maxResults: Int = 5,
        collectionId: String? = null
    ): List<RetrievedChunk> = withContext(Dispatchers.IO) {
        try {
            val vectorResults = retrieveVector(query, maxResults * 2, collectionId)
            val bm25Results   = retrieveLexical(query, maxResults * 2, collectionId)

            if (vectorResults.isEmpty() && bm25Results.isEmpty()) return@withContext emptyList()
            if (vectorResults.isEmpty()) return@withContext bm25Results.take(maxResults)
            if (bm25Results.isEmpty())   return@withContext vectorResults.take(maxResults)

            val scores   = mutableMapOf<String, Float>()
            val chunkMap = mutableMapOf<String, RetrievedChunk>()

            vectorResults.forEachIndexed { rank, chunk ->
                val rrfScore = 1f / (RRF_K + rank + 1)
                scores[chunk.chunkId] = (scores[chunk.chunkId] ?: 0f) + rrfScore
                chunkMap[chunk.chunkId] = chunk
            }
            bm25Results.forEachIndexed { rank, chunk ->
                val rrfScore = 1f / (RRF_K + rank + 1)
                scores[chunk.chunkId] = (scores[chunk.chunkId] ?: 0f) + rrfScore
                chunkMap.putIfAbsent(chunk.chunkId, chunk)
            }

            scores.entries
                .sortedByDescending { it.value }
                .take(maxResults)
                .mapNotNull { (id, score) -> chunkMap[id]?.copy(score = score) }
        } catch (e: Exception) {
            Log.e(TAG, "Hybrid retrieve failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun retrieveVector(
        query: String,
        maxResults: Int = 5,
        collectionId: String? = null
    ): List<RetrievedChunk> = withContext(Dispatchers.IO) {
        try {
            val queryEmbedding = embeddingService.generateEmbedding(query)
                ?: return@withContext emptyList()

            val allChunks = if (collectionId.isNullOrEmpty() || collectionId == "all")
                documentDao.getAllChunks()
            else
                documentDao.getChunksForCollection(collectionId)

            val isGemma   = embeddingService.getModelName().contains("embeddinggemma", ignoreCase = true)
            val threshold = if (isGemma) VECTOR_THRESHOLD_GEMMA else VECTOR_THRESHOLD_GECKO

            allChunks
                .filter { it.embedding != null }
                .mapNotNull { chunk ->
                    val similarity = cosineSimilarity(queryEmbedding, chunk.embedding!!.toFloatArray())
                    if (similarity >= threshold) {
                        RetrievedChunk(
                            chunkId = chunk.id, docId = chunk.docId, fileName = chunk.fileName,
                            chunkIndex = chunk.chunkIndex, content = chunk.content,
                            score = similarity, vectorScore = similarity, bm25Rank = 0
                        )
                    } else null
                }
                .sortedByDescending { it.vectorScore }
                .take(maxResults)
        } catch (e: Exception) {
            Log.e(TAG, "Vector retrieve failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun retrieveLexical(
        query: String,
        maxResults: Int = 5,
        collectionId: String? = null
    ): List<RetrievedChunk> = withContext(Dispatchers.IO) {
        try {
            val sanitized = query.sanitizeForFts()
            if (sanitized.isBlank()) return@withContext emptyList()

            val chunks = if (collectionId.isNullOrEmpty() || collectionId == "all")
                documentDao.searchChunksFts(sanitized, maxResults)
            else
                documentDao.searchChunksFtsForCollection(sanitized, collectionId, maxResults)

            chunks.mapIndexed { rank, chunk ->
                RetrievedChunk(
                    chunkId = chunk.id, docId = chunk.docId, fileName = chunk.fileName,
                    chunkIndex = chunk.chunkIndex, content = chunk.content,
                    score = 1f / (RRF_K + rank + 1), vectorScore = 0f, bm25Rank = rank + 1
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "BM25 retrieve failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
