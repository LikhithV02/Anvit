package com.anvit.localai.eval.report

import com.anvit.localai.eval.runner.EvalRunResult
import java.io.File

object HtmlReporter {
    fun write(file: File, result: EvalRunResult) {
        file.parentFile?.mkdirs()
        file.writeText(render(result))
    }

    private fun render(result: EvalRunResult): String = """
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <title>Anvit Eval ${escape(result.runId)}</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; margin: 32px; line-height: 1.4; }
    table { border-collapse: collapse; width: 100%; }
    th, td { border-bottom: 1px solid #ddd; padding: 8px; text-align: left; vertical-align: top; }
    code, pre { background: #f6f8fa; padding: 2px 4px; border-radius: 4px; }
    pre { white-space: pre-wrap; padding: 12px; }
  </style>
</head>
<body>
  <h1>Anvit Agentic RAG Eval</h1>
  <p><strong>Run:</strong> <code>${escape(result.runId)}</code> · <strong>Samples:</strong> ${result.summary.sampleCount}</p>
  <table>
    <tr><th>ID</th><th>Question</th><th>Recall@5</th><th>Faithfulness</th><th>Answer</th></tr>
    ${result.samples.joinToString("\n") { sample ->
        "<tr><td><code>${escape(sample.sample.id)}</code></td><td>${escape(sample.sample.question)}</td><td>${sample.retrieval.recallAt5}</td><td>${sample.answer.faithfulness}</td><td><pre>${escape(sample.trace.generatedAnswer)}</pre></td></tr>"
    }}
  </table>
</body>
</html>
    """.trimIndent()

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
