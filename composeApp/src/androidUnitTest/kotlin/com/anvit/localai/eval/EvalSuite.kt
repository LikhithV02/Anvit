package com.anvit.localai.eval

import com.anvit.localai.eval.runner.EvalHarness
import com.anvit.localai.eval.metrics.EvalSummary
import com.anvit.localai.eval.report.BaselineCompare
import com.anvit.localai.eval.report.BaselineThresholds
import com.anvit.localai.eval.services.ApiKeyProvider
import com.anvit.localai.eval.services.GeminiClient
import com.anvit.localai.eval.dataset.EvalDataset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class EvalSuite {
    @Test
    fun runEval() = runBlocking {
        val requireKey = property("anvit.eval.requireApiKey", "false").toBoolean()
        val apiKey = ApiKeyProvider.geminiApiKey(required = false)
        if (apiKey.isNullOrBlank()) {
            if (requireKey) error("GEMINI_API_KEY is required for the Anvit eval suite.")
            println("GEMINI_API_KEY is not set; skipping eval in normal unit-test runs.")
            return@runBlocking
        }

        val root = File(property("user.dir", ".")).parentFile ?: File(".")
        val dataset = fileProperty("anvit.eval.dataset", File(root, "eval/datasets/v1/dataset.json"), root)
        val defaultCorpus = File(root, "eval/corpus").takeIf { dir ->
            dir.listFiles { file -> file.isFile && (file.extension.equals("pdf", true) || file.extension.equals("docx", true)) }.orEmpty().isNotEmpty()
        } ?: File(root, "Test Docs")
        val corpus = fileProperty("anvit.eval.corpus", defaultCorpus, root)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val output = fileProperty("anvit.eval.output", File(root, "eval/reports/$timestamp"), root)

        val client = GeminiClient(apiKey!!)
        println("[EvalSuite] Pipeline backend: ${client.pipelineBackendDescription()}")

        val result = EvalHarness(
            client = client,
            datasetFile = dataset,
            corpusDir = corpus,
            outputDir = output
        ).run()

        println("Eval complete: ${output.absolutePath}")
        println("Recall@5=${result.summary.recallAt5}, faithfulness=${result.summary.faithfulness}")

        if (property("anvit.eval.enforceBaseline", "false").toBoolean()) {
            val baselineFile = File(root, "eval/baseline.json")
            if (baselineFile.exists()) {
                val baseline = EvalDataset.json.decodeFromString(EvalSummary.serializer(), baselineFile.readText())
                val failures = BaselineCompare.failures(result.summary, baseline, BaselineThresholds())
                check(failures.isEmpty()) { failures.joinToString(separator = "\n") }
            }
        }
    }

    private fun property(name: String, defaultValue: String): String =
        System.getProperty(name) ?: defaultValue

    private fun fileProperty(name: String, defaultValue: File, root: File): File {
        val raw = System.getProperty(name) ?: return defaultValue
        val file = File(raw)
        return if (file.isAbsolute) file else File(root, raw)
    }
}
