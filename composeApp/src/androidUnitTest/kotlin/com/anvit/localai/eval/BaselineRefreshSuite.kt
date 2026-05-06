package com.anvit.localai.eval

import com.anvit.localai.eval.dataset.EvalDataset
import com.anvit.localai.eval.metrics.EvalSummary
import com.anvit.localai.eval.runner.EvalHarness
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
class BaselineRefreshSuite {
    @Test
    fun refreshBaseline() = runBlocking {
        val apiKey = ApiKeyProvider.geminiApiKey(required = true)
        val root = File(property("user.dir", ".")).parentFile ?: File(".")
        val dataset = File(property("anvit.eval.dataset", File(root, "eval/datasets/v1/dataset.json").absolutePath))
        val defaultCorpus = File(root, "eval/corpus").takeIf { dir ->
            dir.listFiles { file -> file.isFile && (file.extension.equals("pdf", true) || file.extension.equals("docx", true)) }.orEmpty().isNotEmpty()
        } ?: File(root, "Test Docs")
        val corpus = File(property("anvit.eval.corpus", defaultCorpus.absolutePath))
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val output = File(root, "eval/reports/baseline-$timestamp")
        val result = EvalHarness(GeminiClient(apiKey!!), dataset, corpus, output).run()
        File(root, "eval/baseline.json").writeText(EvalDataset.json.encodeToString(EvalSummary.serializer(), result.summary))
        println("Refreshed eval baseline at ${File(root, "eval/baseline.json").absolutePath}")
    }

    private fun property(name: String, defaultValue: String): String =
        System.getProperty(name) ?: defaultValue
}
