package com.anvit.localai.eval

import com.anvit.localai.eval.dataset.EvalDataset
import com.anvit.localai.eval.device.DeviceEvalJson
import com.anvit.localai.eval.device.DeviceEvalRunArtifacts
import com.anvit.localai.eval.metrics.AnswerJudge
import com.anvit.localai.eval.metrics.MetricsAggregator
import com.anvit.localai.eval.metrics.PerfScore
import com.anvit.localai.eval.metrics.RetrievalMetrics
import com.anvit.localai.eval.report.HtmlReporter
import com.anvit.localai.eval.report.MarkdownReporter
import com.anvit.localai.eval.runner.EvalRunResult
import com.anvit.localai.eval.runner.ScoredEvalSample
import com.anvit.localai.eval.services.ApiKeyProvider
import com.anvit.localai.eval.services.GeminiClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class DeviceEvalScoringSuite {
    @Test
    fun scoreDeviceEval() = runBlocking {
        val apiKey = ApiKeyProvider.geminiApiKey(required = true)
        val root = File(property("user.dir", ".")).parentFile ?: File(".")
        val datasetFile = File(property("anvit.eval.dataset", File(root, "eval/datasets/v1/dataset.json").absolutePath))
        val artifactDir = File(property("anvit.eval.deviceArtifacts", ""))
        require(artifactDir.isDirectory) {
            "anvit.eval.deviceArtifacts must point to a pulled device eval artifact directory."
        }

        val artifactsFile = File(artifactDir, "device_eval_run.json")
        require(artifactsFile.isFile) {
            "Missing device_eval_run.json in ${artifactDir.absolutePath}."
        }

        val dataset = EvalDataset.load(datasetFile)
        val artifacts = DeviceEvalJson.format.decodeFromString(
            DeviceEvalRunArtifacts.serializer(),
            artifactsFile.readText()
        )
        val sampleById = dataset.samples.associateBy { it.id }
        val tracePairs = artifacts.traces.mapNotNull { record ->
            sampleById[record.sampleId]?.let { sample -> sample to record.trace }
        }
        require(tracePairs.isNotEmpty()) {
            "No matching sample IDs between ${datasetFile.absolutePath} and ${artifactsFile.absolutePath}."
        }

        val judge = AnswerJudge(GeminiClient(apiKey!!))
        val judgeScores = if (property("anvit.eval.disableBatchJudge", "false").toBoolean()) {
            tracePairs.associate { (sample, trace) -> sample.id to judge.score(sample, trace) }
        } else {
            runCatching { judge.scoreBatch(tracePairs) }
                .getOrElse { error ->
                    println("[DeviceEvalScoring] Batch judge failed, falling back to immediate judge: ${error.message}")
                    tracePairs.associate { (sample, trace) -> sample.id to judge.score(sample, trace) }
                }
        }
        val scored = tracePairs.map { (sample, trace) ->
            ScoredEvalSample(
                sample = sample,
                trace = trace,
                retrieval = RetrievalMetrics.score(sample, trace),
                answer = judgeScores[sample.id] ?: judge.score(sample, trace),
                perf = PerfScore.from(trace)
            )
        }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val output = File(property("anvit.eval.output", File(root, "eval/reports/device-$timestamp").absolutePath))
        val result = EvalRunResult(
            runId = artifacts.runId,
            gitSha = System.getProperty("anvit.eval.gitSha") ?: "unknown",
            generatedAt = artifacts.generatedAt,
            summary = MetricsAggregator.summarize(scored),
            samples = scored
        )

        output.mkdirs()
        File(output, "metrics.json").writeText(EvalDataset.json.encodeToString(EvalRunResult.serializer(), result))
        File(output, "device_ingestion_manifest.json").writeText(
            File(artifactDir, "device_ingestion_manifest.json").readText()
        )
        MarkdownReporter.write(File(output, "summary.md"), result)
        HtmlReporter.write(File(output, "details.html"), result)

        println("Device eval scoring complete: ${output.absolutePath}")
        println("Recall@5=${result.summary.recallAt5}, faithfulness=${result.summary.faithfulness}")
    }

    private fun property(name: String, defaultValue: String): String =
        System.getProperty(name) ?: defaultValue
}
