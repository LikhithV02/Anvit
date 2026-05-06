package com.anvit.localai.eval.metrics

import com.anvit.localai.agentic.PipelineTrace
import kotlinx.serialization.Serializable

@Serializable
data class PerfScore(
    val totalLatencyMs: Long,
    val stageLatencyMs: Map<String, Long>
) {
    companion object {
        fun from(trace: PipelineTrace): PerfScore = PerfScore(
            totalLatencyMs = trace.totalLatencyMs,
            stageLatencyMs = trace.latencyMs
        )
    }
}
