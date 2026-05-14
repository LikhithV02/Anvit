package com.anvit.localai.embedding

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GeckoEmbeddingServiceTest {

    @Test
    fun preferredEmbeddingModelFileUsesEmbeddingGemmaWhenBothModelsExist() {
        val modelsDir = Files.createTempDirectory("embedding-models").toFile()
        try {
            File(modelsDir, "Gecko_512_quant.tflite").writeText("gecko")
            File(modelsDir, "embeddinggemma-300M_seq2048_mixed-precision.tflite").writeText("gemma")

            val preferred = GeckoEmbeddingService.preferredEmbeddingModelFile(modelsDir)

            assertEquals("embeddinggemma-300M_seq2048_mixed-precision.tflite", preferred?.name)
        } finally {
            modelsDir.deleteRecursively()
        }
    }
}
