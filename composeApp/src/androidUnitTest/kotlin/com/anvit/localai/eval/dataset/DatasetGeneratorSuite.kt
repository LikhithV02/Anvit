package com.anvit.localai.eval.dataset

import com.anvit.localai.eval.services.ApiKeyProvider
import com.anvit.localai.eval.services.GeminiClient
import org.junit.Test
import java.io.File

class DatasetGeneratorSuite {
    @Test
    fun regenerateDataset() {
        val root = File(property("user.dir", ".")).parentFile ?: File(".")
        val csvDir = File(property("anvit.eval.chunks", File(root, "Test Docs").absolutePath))
        val output = File(property("anvit.eval.dataset", File(root, "eval/datasets/v2/dataset.json").absolutePath))
        val apiKey = ApiKeyProvider.geminiApiKey(required = true)
        val dataset = DatasetGenerator(GeminiClient(apiKey!!)).generateFromCsvExports(
            csvDir = csvDir,
            output = output,
            singleHop = property("anvit.eval.singleHop", "20").toInt(),
            multiHop = property("anvit.eval.multiHop", "20").toInt(),
            tableLookup = property("anvit.eval.tableLookup", "10").toInt(),
            adversarial = property("anvit.eval.adversarial", "10").toInt()
        )
        println("Wrote ${dataset.samples.size} samples to ${output.absolutePath}")
    }

    private fun property(name: String, defaultValue: String): String =
        System.getProperty(name) ?: defaultValue
}
