package com.anvit.localai.eval.runner

import com.anvit.localai.agentic.PipelineTrace
import com.anvit.localai.eval.dataset.EvalSample
import com.anvit.localai.eval.metrics.AnswerJudgeScore
import com.anvit.localai.eval.metrics.PerfScore
import com.anvit.localai.eval.metrics.RetrievalScore
import kotlinx.serialization.Serializable

@Serializable
data class ScoredEvalSample(
    val sample: EvalSample,
    val trace: PipelineTrace,
    val retrieval: RetrievalScore,
    val answer: AnswerJudgeScore,
    val perf: PerfScore
)

@Serializable
data class EvalRunResult(
    val runId: String,
    val gitSha: String,
    val generatedAt: String,
    val summary: com.anvit.localai.eval.metrics.EvalSummary,
    val samples: List<ScoredEvalSample>
)
