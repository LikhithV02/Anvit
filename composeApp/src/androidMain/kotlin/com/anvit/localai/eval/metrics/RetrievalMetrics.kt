package com.anvit.localai.eval.metrics

import com.anvit.localai.agentic.PipelineTrace
import com.anvit.localai.eval.dataset.EvalSample
import kotlinx.serialization.Serializable
import kotlin.math.ln

@Serializable
data class RetrievalScore(
    val recallAt3: Double,
    val recallAt5: Double,
    val recallAt10: Double,
    val precisionAt5: Double,
    val mrr: Double,
    val ndcgAt5: Double,
    val hitRate: Double,
    val perDocRecall: Double,
    val subQueryCoverage: Double,
    val groupExpansionHit: Boolean
)

object RetrievalMetrics {
    fun score(sample: EvalSample, trace: PipelineTrace): RetrievalScore {
        val ranked = trace.finalChunkIds.ifEmpty { trace.dedupedChunkIds }
        val rankedHashes = if (trace.finalChunks.isNotEmpty()) {
            trace.finalChunks.map { it.contentHash }
        } else {
            emptyList()
        }
        val expected = sample.expectedChunkIds.toSet()
        val expectedHashes = sample.expectedContentHashes.toSet()
        val expectedDocs = sample.expectedDocIds.toSet()
        return RetrievalScore(
            recallAt3 = relevanceRecall(ranked.take(3), rankedHashes.take(3), expected, expectedHashes),
            recallAt5 = relevanceRecall(ranked.take(5), rankedHashes.take(5), expected, expectedHashes),
            recallAt10 = relevanceRecall(ranked.take(10), rankedHashes.take(10), expected, expectedHashes),
            precisionAt5 = relevancePrecision(ranked.take(5), rankedHashes.take(5), expected, expectedHashes),
            mrr = relevanceMrr(ranked, rankedHashes, expected, expectedHashes),
            ndcgAt5 = relevanceNdcg(ranked.take(5), rankedHashes.take(5), expected, expectedHashes),
            hitRate = if (expected.isEmpty() && expectedHashes.isEmpty()) 0.0 else if (ranked.indices.any { isRelevant(ranked[it], rankedHashes.getOrNull(it), expected, expectedHashes) }) 1.0 else 0.0,
            perDocRecall = perDocRecall(trace.finalChunks.map { it.docId }, expectedDocs),
            subQueryCoverage = subQueryCoverage(trace.subQueries, trace.retrievedChunkIds, expected),
            groupExpansionHit = trace.finalChunks.any { chunk ->
                chunk.groupId != null && trace.finalChunks.count { it.groupId == chunk.groupId } > 1
            }
        )
    }

    private fun relevanceRecall(
        retrievedIds: List<String>,
        retrievedHashes: List<String>,
        expectedIds: Set<String>,
        expectedHashes: Set<String>
    ): Double {
        if (expectedIds.isEmpty() && expectedHashes.isEmpty()) return 1.0
        val idHits = retrievedIds.filter { it in expectedIds }.toSet().size
        val hashHits = retrievedHashes.filter { it in expectedHashes }.toSet().size
        val denominator = if (expectedHashes.isNotEmpty()) expectedHashes.size else expectedIds.size
        return (maxOf(idHits, hashHits).toDouble() / denominator).coerceAtMost(1.0)
    }

    private fun relevancePrecision(
        retrievedIds: List<String>,
        retrievedHashes: List<String>,
        expectedIds: Set<String>,
        expectedHashes: Set<String>
    ): Double {
        if (retrievedIds.isEmpty()) return if (expectedIds.isEmpty() && expectedHashes.isEmpty()) 1.0 else 0.0
        val hits = retrievedIds.indices.mapNotNull { index ->
            relevanceKey(retrievedIds[index], retrievedHashes.getOrNull(index), expectedIds, expectedHashes)
        }.toSet().size
        return hits.toDouble() / retrievedIds.size
    }

    private fun relevanceMrr(
        rankedIds: List<String>,
        rankedHashes: List<String>,
        expectedIds: Set<String>,
        expectedHashes: Set<String>
    ): Double {
        if (expectedIds.isEmpty() && expectedHashes.isEmpty()) return 1.0
        val rank = rankedIds.indices.indexOfFirst { isRelevant(rankedIds[it], rankedHashes.getOrNull(it), expectedIds, expectedHashes) }
        return if (rank < 0) 0.0 else 1.0 / (rank + 1)
    }

    private fun relevanceNdcg(
        rankedIds: List<String>,
        rankedHashes: List<String>,
        expectedIds: Set<String>,
        expectedHashes: Set<String>
    ): Double {
        if (expectedIds.isEmpty() && expectedHashes.isEmpty()) return 1.0
        val expectedSize = if (expectedHashes.isNotEmpty()) expectedHashes.size else expectedIds.size
        val seen = mutableSetOf<String>()
        val dcg = rankedIds.indices.map { index ->
            val key = relevanceKey(rankedIds[index], rankedHashes.getOrNull(index), expectedIds, expectedHashes)
            if (key != null && seen.add(key)) 1.0 / log2(index + 2.0) else 0.0
        }.sum()
        val ideal = List(minOf(expectedSize, rankedIds.size)) { index -> 1.0 / log2(index + 2.0) }.sum()
        return if (ideal == 0.0) 0.0 else dcg / ideal
    }

    private fun isRelevant(id: String, hash: String?, expectedIds: Set<String>, expectedHashes: Set<String>): Boolean =
        id in expectedIds || (hash != null && hash in expectedHashes)

    private fun relevanceKey(id: String, hash: String?, expectedIds: Set<String>, expectedHashes: Set<String>): String? =
        when {
            hash != null && hash in expectedHashes -> "hash:$hash"
            id in expectedIds -> "id:$id"
            else -> null
        }

    private fun perDocRecall(retrievedDocs: List<String>, expectedDocs: Set<String>): Double =
        if (expectedDocs.isEmpty()) 1.0 else retrievedDocs.toSet().count { it in expectedDocs }.toDouble() / expectedDocs.size

    private fun subQueryCoverage(subQueries: List<String>, retrievedIds: List<String>, expected: Set<String>): Double =
        if (subQueries.isEmpty() || expected.isEmpty()) 1.0 else if (retrievedIds.any { it in expected }) 1.0 else 0.0

    private fun log2(value: Double): Double = ln(value) / ln(2.0)
}
