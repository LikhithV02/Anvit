package com.anvit.localai.eval.report

import com.anvit.localai.eval.metrics.EvalSummary
import kotlinx.serialization.Serializable

@Serializable
data class BaselineThresholds(
    val recallAt5Drop: Double = 0.02,
    val perDocRecallDrop: Double = 0.03,
    val faithfulnessDrop: Double = 0.05,
    val latencyP95Regression: Double = 0.30
)

object BaselineCompare {
    fun failures(current: EvalSummary, baseline: EvalSummary, thresholds: BaselineThresholds): List<String> = buildList {
        if (baseline.sampleCount == 0) return@buildList
        if (baseline.recallAt5 - current.recallAt5 > thresholds.recallAt5Drop) {
            add("Recall@5 dropped from ${baseline.recallAt5} to ${current.recallAt5}")
        }
        if (baseline.perDocRecall - current.perDocRecall > thresholds.perDocRecallDrop) {
            add("Per-doc recall dropped from ${baseline.perDocRecall} to ${current.perDocRecall}")
        }
        if (baseline.faithfulness - current.faithfulness > thresholds.faithfulnessDrop) {
            add("Faithfulness dropped from ${baseline.faithfulness} to ${current.faithfulness}")
        }
        if (baseline.latencyP95Ms > 0 && current.latencyP95Ms > baseline.latencyP95Ms * (1.0 + thresholds.latencyP95Regression)) {
            add("Latency p95 regressed from ${baseline.latencyP95Ms}ms to ${current.latencyP95Ms}ms")
        }
    }
}
