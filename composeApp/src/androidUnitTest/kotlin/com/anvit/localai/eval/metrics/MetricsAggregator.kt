package com.anvit.localai.eval.metrics

import com.anvit.localai.eval.runner.ScoredEvalSample
import kotlinx.serialization.Serializable

@Serializable
data class EvalSummary(
    val sampleCount: Int,
    val recallAt5: Double,
    val perDocRecall: Double,
    val mrr: Double,
    val faithfulness: Double,
    val correctness: Double,
    val latencyP95Ms: Long
)

object MetricsAggregator {
    fun summarize(results: List<ScoredEvalSample>): EvalSummary {
        if (results.isEmpty()) {
            return EvalSummary(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        }
        val latencies = results.map { it.perf.totalLatencyMs }.sorted()
        return EvalSummary(
            sampleCount = results.size,
            recallAt5 = results.map { it.retrieval.recallAt5 }.average(),
            perDocRecall = results.map { it.retrieval.perDocRecall }.average(),
            mrr = results.map { it.retrieval.mrr }.average(),
            faithfulness = results.map { it.answer.faithfulness }.average(),
            correctness = results.map { it.answer.correctness }.average(),
            latencyP95Ms = latencies[((latencies.size - 1) * 0.95).toInt()]
        )
    }
}
