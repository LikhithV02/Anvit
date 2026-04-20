package com.anvit.localai.retrieval

import com.anvit.localai.data.db.DocumentDao
import com.anvit.localai.embedding.EmbeddingService
import com.anvit.localai.utils.sanitizeForFts
import com.anvit.localai.utils.toFloatArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

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

    suspend fun retrieve(query: String, maxResults: Int = 5, collectionId: String? = null): List<RetrievedChunk> =
        withContext(Dispatchers.IO) {
            try {
                val vectorResults = retrieveVector(query, maxResults * 2, collectionId)
                val bm25Results   = retrieveLexical(query, maxResults * 2, collectionId)
                if (vectorResults.isEmpty() && bm25Results.isEmpty()) return@withContext emptyList()
                if (vectorResults.isEmpty()) return@withContext bm25Results.take(maxResults)
                if (bm25Results.isEmpty())   return@withContext vectorResults.take(maxResults)
                val scores   = mutableMapOf<String, Float>()
                val chunkMap = mutableMapOf<String, RetrievedChunk>()
                vectorResults.forEachIndexed { rank, chunk ->
                    scores[chunk.chunkId] = (scores[chunk.chunkId] ?: 0f) + 1f / (RRF_K + rank + 1)
                    chunkMap[chunk.chunkId] = chunk
                }
                bm25Results.forEachIndexed { rank, chunk ->
                    scores[chunk.chunkId] = (scores[chunk.chunkId] ?: 0f) + 1f / (RRF_K + rank + 1)
                    chunkMap.getOrPut(chunk.chunkId) { chunk }
                }
                scores.entries.sortedByDescending { it.value }.take(maxResults)
                    .mapNotNull { (id, score) -> chunkMap[id]?.copy(score = score) }
            } catch (e: Exception) {
                println("[$TAG] Hybrid retrieve failed: ${e.message}")
                emptyList()
            }
        }

    suspend fun retrieveVector(query: String, maxResults: Int = 5, collectionId: String? = null): List<RetrievedChunk> =
        withContext(Dispatchers.IO) {
            try {
                val queryEmbedding = embeddingService.generateEmbedding(query) ?: return@withContext emptyList()
                val allChunks = if (collectionId.isNullOrEmpty() || collectionId == "all")
                    documentDao.getAllChunks() else documentDao.getChunksForCollection(collectionId)
                val isGemma   = embeddingService.getModelName().contains("embeddinggemma", ignoreCase = true)
                val threshold = if (isGemma) VECTOR_THRESHOLD_GEMMA else VECTOR_THRESHOLD_GECKO
                allChunks.filter { it.embedding != null }
                    .mapNotNull { chunk ->
                        val sim = cosineSimilarity(queryEmbedding, chunk.embedding!!.toFloatArray())
                        if (sim >= threshold) RetrievedChunk(chunk.id, chunk.docId, chunk.fileName, chunk.chunkIndex, chunk.content, sim, sim, 0) else null
                    }
                    .sortedByDescending { it.vectorScore }.take(maxResults)
            } catch (e: Exception) {
                println("[$TAG] Vector retrieve failed: ${e.message}")
                emptyList()
            }
        }

    suspend fun retrieveLexical(query: String, maxResults: Int = 5, collectionId: String? = null): List<RetrievedChunk> =
        withContext(Dispatchers.IO) {
            try {
                val sanitized = query.sanitizeForFts()
                if (sanitized.isBlank()) return@withContext emptyList()
                val chunks = if (collectionId.isNullOrEmpty() || collectionId == "all")
                    documentDao.searchChunksFts(sanitized, maxResults)
                else documentDao.searchChunksFtsForCollection(sanitized, collectionId, maxResults)
                chunks.mapIndexed { rank, chunk ->
                    RetrievedChunk(chunk.id, chunk.docId, chunk.fileName, chunk.chunkIndex, chunk.content,
                        1f / (RRF_K + rank + 1), 0f, rank + 1)
                }
            } catch (e: Exception) {
                println("[$TAG] BM25 retrieve failed: ${e.message}")
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
