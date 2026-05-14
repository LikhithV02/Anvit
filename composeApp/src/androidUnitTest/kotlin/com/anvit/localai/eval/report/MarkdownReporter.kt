package com.anvit.localai.eval.report

import com.anvit.localai.eval.runner.EvalRunResult
import java.io.File
import kotlin.math.roundToInt

object MarkdownReporter {
    fun write(file: File, result: EvalRunResult) {
        file.parentFile?.mkdirs()
        file.writeText(render(result))
    }

    fun render(result: EvalRunResult): String = buildString {
        appendLine("<!-- anvit-eval-comment -->")
        appendLine("# Anvit Agentic RAG Eval")
        appendLine()
        appendLine("- Run: `${result.runId}`")
        appendLine("- Git SHA: `${result.gitSha}`")
        appendLine("- Samples: ${result.summary.sampleCount}")
        result.metadata["pipeline_backend"]?.takeIf { it.isNotBlank() }?.let {
            appendLine("- Pipeline: `$it`")
        }
        result.metadata["dataset_version"]?.takeIf { it.isNotBlank() }?.let {
            appendLine("- Dataset: `$it`")
        }
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|---|---:|")
        appendLine("| Recall@5 | ${pct(result.summary.recallAt5)} |")
        appendLine("| Multi-doc recall | ${pct(result.summary.perDocRecall)} |")
        appendLine("| MRR | ${pct(result.summary.mrr)} |")
        appendLine("| Faithfulness | ${pct(result.summary.faithfulness)} |")
        appendLine("| Correctness | ${pct(result.summary.correctness)} |")
        appendLine("| Latency p95 | ${result.summary.latencyP95Ms} ms |")
        appendLine()
        appendLine("## Lowest Recall Items")
        result.samples.sortedBy { it.retrieval.recallAt5 }.take(10).forEach { sample ->
            appendLine("- `${sample.sample.id}` ${pct(sample.retrieval.recallAt5)}: ${sample.sample.question}")
        }
    }

    private fun pct(value: Double): String = "${(value * 100).roundToInt()}%"
}
