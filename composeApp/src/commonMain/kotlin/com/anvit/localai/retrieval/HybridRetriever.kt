package com.anvit.localai.retrieval

import com.anvit.localai.data.db.DocumentDao
import com.anvit.localai.data.db.entities.ChunkEntity
import com.anvit.localai.document.toRetrievedChunk
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
        private const val VECTOR_THRESHOLD_GEMINI = 0.45f
        private const val VECTOR_THRESHOLD_FLOOR = 0.30f
        private const val DEFAULT_VECTOR_WEIGHT = 0.75f
        private const val DEFAULT_LEXICAL_WEIGHT = 0.25f
        private const val TABLE_VECTOR_WEIGHT = 0.45f
        private const val TABLE_LEXICAL_WEIGHT = 0.55f
        private const val MAX_GROUP_EXPANSION_CHUNKS = 3
        private const val MAX_OCR_RELATION_EXPANSION_CHUNKS = 4
    }

    suspend fun retrieve(query: String, maxResults: Int = 5, collectionId: String? = null): List<RetrievedChunk> =
        withContext(Dispatchers.IO) {
            try {
                val vectorResults = retrieveVector(query, maxResults * 4, collectionId)
                val bm25Results   = retrieveLexical(query, maxResults * 4, collectionId)
                if (vectorResults.isEmpty() && bm25Results.isEmpty()) return@withContext emptyList()
                if (vectorResults.isEmpty()) return@withContext expandOcrRelations(expandGroups(bm25Results.take(maxResults)))
                if (bm25Results.isEmpty())   return@withContext expandOcrRelations(expandGroups(vectorResults.take(maxResults)))
                val weights = fusionWeights(query)
                val scores   = mutableMapOf<String, Float>()
                val chunkMap = mutableMapOf<String, RetrievedChunk>()
                vectorResults.forEachIndexed { rank, chunk ->
                    scores[chunk.chunkId] = (scores[chunk.chunkId] ?: 0f) +
                        weights.vector * (1f / (RRF_K + rank + 1))
                    chunkMap[chunk.chunkId] = chunk
                }
                bm25Results.forEachIndexed { rank, chunk ->
                    scores[chunk.chunkId] = (scores[chunk.chunkId] ?: 0f) +
                        weights.lexical * (1f / (RRF_K + rank + 1))
                    val existing = chunkMap[chunk.chunkId]
                    chunkMap[chunk.chunkId] = if (existing != null) {
                        existing.copy(
                            bm25Rank = chunk.bm25Rank,
                            lexicalScore = chunk.lexicalScore,
                            ftsQuery = chunk.ftsQuery,
                            retrievalSource = "hybrid"
                        )
                    } else {
                        chunk
                    }
                }
                val ranked = scores.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Float>> { it.value }
                            .thenByDescending { chunkMap[it.key]?.vectorScore ?: 0f }
                            .thenByDescending { chunkMap[it.key]?.lexicalScore ?: 0f }
                    )
                    .take(maxResults)
                    .mapNotNull { (id, score) -> chunkMap[id]?.copy(score = score) }
                expandOcrRelations(expandGroups(ranked))
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
                val threshold = vectorThreshold()
                val ranked = allChunks.filter { it.embedding != null }
                    .map { chunk ->
                        val sim = cosineSimilarity(queryEmbedding, chunk.embedding!!.toFloatArray())
                        chunk to sim
                    }
                    .sortedByDescending { it.second }

                ranked.mapNotNull { (chunk, sim) ->
                        if (sim >= threshold) RetrievedChunk(
                            chunk.id, chunk.docId, chunk.fileName, chunk.chunkIndex, chunk.content,
                            sim, sim, 0, chunk.groupId, retrievalSource = "vector"
                        ) else null
                    }
                    .ifEmpty {
                        ranked.take(maxResults).mapNotNull { (chunk, sim) ->
                            if (sim >= VECTOR_THRESHOLD_FLOOR) RetrievedChunk(
                                chunk.id, chunk.docId, chunk.fileName, chunk.chunkIndex, chunk.content,
                                sim, sim, 0, chunk.groupId, retrievalSource = "vector_fallback"
                            ) else null
                        }
                    }
                    .take(maxResults)
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
                val candidateLimit = maxResults * 6
                val chunks = if (collectionId.isNullOrEmpty() || collectionId == "all")
                    documentDao.searchChunksFts(sanitized, candidateLimit)
                else documentDao.searchChunksFtsForCollection(sanitized, collectionId, candidateLimit)
                val terms = FtsQueryBuilder.terms(query)
                chunks.asSequence()
                    .distinctBy { it.id }
                    .map { it to lexicalScore(query, terms, it) }
                    .filter { it.second > 0f }
                    .sortedByDescending { it.second }
                    .take(maxResults)
                    .mapIndexed { rank, (chunk, lexicalScore) ->
                    RetrievedChunk(
                        chunk.id, chunk.docId, chunk.fileName, chunk.chunkIndex, chunk.content,
                        1f / (RRF_K + rank + 1),
                        0f,
                        rank + 1,
                        chunk.groupId,
                        lexicalScore = lexicalScore,
                        ftsQuery = sanitized,
                        retrievalSource = "lexical"
                    )
                }.toList()
            } catch (e: Exception) {
                println("[$TAG] BM25 retrieve failed: ${e.message}")
                emptyList()
            }
        }

    private suspend fun expandGroups(chunks: List<RetrievedChunk>): List<RetrievedChunk> {
        val groupIds = chunks.mapNotNull { it.groupId }.distinct()
        if (groupIds.isEmpty()) return chunks

        val expanded = chunks.toMutableList()
        val seenIds = chunks.map { it.chunkId }.toMutableSet()

        for (gid in groupIds) {
            val groupChunks = documentDao.getChunksForGroup(gid)
            val matchedIndices = chunks
                .filter { it.groupId == gid }
                .map { it.chunkIndex }
                .toSet()
            val headChunks = groupChunks.filter { it.isGroupHead }
            val nearbyChunks = groupChunks
                .filter { entity ->
                    !entity.isGroupHead && matchedIndices.any { index ->
                        kotlin.math.abs(entity.chunkIndex - index) <= 1
                    }
                }
            val expansion = (headChunks + nearbyChunks)
                .distinctBy { it.id }
                .take(MAX_GROUP_EXPANSION_CHUNKS)

            for (entity in expansion) {
                if (entity.id !in seenIds) {
                    expanded.add(entity.toRetrievedChunk(score = 0f))
                    seenIds.add(entity.id)
                }
            }
        }

        return expanded.sortedWith(
            compareByDescending<RetrievedChunk> { it.score }.thenBy { it.chunkIndex }
        )
    }

    private suspend fun expandOcrRelations(chunks: List<RetrievedChunk>): List<RetrievedChunk> {
        if (chunks.isEmpty()) return chunks

        val expanded = chunks.toMutableList()
        val seenIds = chunks.map { it.chunkId }.toMutableSet()
        var added = 0

        for (chunk in chunks) {
            if (added >= MAX_OCR_RELATION_EXPANSION_CHUNKS) break
            val entity = documentDao.getChunkEntityById(chunk.chunkId) ?: continue

            val parentId = entity.parentChunkId
            if (parentId != null && parentId !in seenIds && added < MAX_OCR_RELATION_EXPANSION_CHUNKS) {
                documentDao.getChunkEntityById(parentId)?.let { parent ->
                    expanded.add(parent.toRetrievedChunk(score = 0f))
                    seenIds.add(parent.id)
                    added++
                }
            }

            val sectionId = entity.sectionId
            if (sectionId.isNullOrBlank() || added >= MAX_OCR_RELATION_EXPANSION_CHUNKS) continue

            val siblings = documentDao.getChunksForSection(sectionId)
                .filter { sibling ->
                    sibling.id !in seenIds &&
                        sibling.chunkType != "SECTION" &&
                        kotlin.math.abs(sibling.chunkIndex - entity.chunkIndex) <= 1
                }
                .take(MAX_OCR_RELATION_EXPANSION_CHUNKS - added)

            for (sibling in siblings) {
                expanded.add(sibling.toRetrievedChunk(score = 0f))
                seenIds.add(sibling.id)
                added++
            }
        }

        return expanded.sortedWith(
            compareByDescending<RetrievedChunk> { it.score }.thenBy { it.chunkIndex }
        )
    }

    private fun vectorThreshold(): Float {
        val model = embeddingService.getModelName()
        return when {
            model.contains("embeddinggemma", ignoreCase = true) -> VECTOR_THRESHOLD_GEMMA
            model.contains("gemini-embedding", ignoreCase = true) -> VECTOR_THRESHOLD_GEMINI
            model.contains("gecko", ignoreCase = true) -> VECTOR_THRESHOLD_GECKO
            else -> VECTOR_THRESHOLD_GEMMA
        }
    }

    private fun fusionWeights(query: String): FusionWeights {
        val q = query.lowercase()
        val tableSignals = listOf(
            "table", " col ", " column ", " row ", " under ", "amount", "value",
            "inventory turnover", "ebit", "ebitda", "revenue", "profit", "margin",
            "borrowings", "assets", "liabilities", "stock-in-trade", "in crore"
        )
        return if (tableSignals.any { q.contains(it) }) {
            FusionWeights(TABLE_VECTOR_WEIGHT, TABLE_LEXICAL_WEIGHT)
        } else {
            FusionWeights(DEFAULT_VECTOR_WEIGHT, DEFAULT_LEXICAL_WEIGHT)
        }
    }

    private data class FusionWeights(val vector: Float, val lexical: Float)

    private fun lexicalScore(originalQuery: String, terms: List<String>, chunk: ChunkEntity): Float {
        val content = chunk.content.lowercase()
        val path = chunk.hierarchyPath.lowercase()
        val file = chunk.fileName.lowercase()
        val original = originalQuery.lowercase()
        var score = 0f

        terms.forEach { term ->
            if (content.contains(term)) score += 3f
            if (path.contains(term)) score += 1.5f
            if (file.contains(term)) score += 0.5f
        }

        val meaningfulPhrases = original.split(Regex("[,;?]"))
            .map { it.trim() }
            .filter { it.length >= 8 }
        meaningfulPhrases.forEach { phrase ->
            if (content.contains(phrase)) score += 8f
        }

        if (content.startsWith("table:", ignoreCase = true) || content.contains("\ntable:", ignoreCase = true)) {
            score += terms.count { term -> content.contains("$term:") }.toFloat() * 2.5f
            score += 1f
        }

        if (chunk.chunkType == "TABLE" || chunk.chunkType == "TABLE_PART") {
            score += terms.count { term -> content.contains("$term:") || content.contains("| $term", ignoreCase = true) }.toFloat() * 2.5f
            score += 1f
        }

        extractNumericTerms(original).forEach { numeric ->
            if (content.contains(numeric)) score += 4f
        }

        if (chunk.groupId != null) score += 0.5f
        return score
    }

    private fun extractNumericTerms(query: String): List<String> =
        Regex("""[-(]?\d[\d,]*(?:\.\d+)?%?[)]?""")
            .findAll(query)
            .map { it.value.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .toList()

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
