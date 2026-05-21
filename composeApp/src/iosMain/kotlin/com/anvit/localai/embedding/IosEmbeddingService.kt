package com.anvit.localai.embedding

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDomainMask
import kotlin.math.sqrt

/**
 * iOS implementation of [EmbeddingService].
 *
 * The downloaded Gecko / EmbeddingGemma files are Android LiteRT TFLite assets.
 * Until a native iOS TFLite/MediaPipe embedder is wired in, this service uses
 * their presence as the local-document-search gate and produces deterministic
 * lexical vectors so ingestion and hybrid retrieval work on iOS/macOS builds.
 */
class IosEmbeddingService : EmbeddingService {
    private var initialized = false
    private var modelName = "None"

    override suspend fun initialize(): Boolean {
        val modelFile = preferredEmbeddingModelFile()
        if (modelFile == null) {
            println("IosEmbeddingService: no embedding model found in $modelsDir")
            initialized = false
            modelName = "None"
            return false
        }

        initialized = true
        modelName = "iOS local lexical embedding (${modelFile.substringAfterLast('/')})"
        println("IosEmbeddingService: embedding model ready: $modelName")
        return true
    }

    override suspend fun generateEmbedding(text: String): FloatArray? {
        if (!initialized && !initialize()) return null

        val clean = text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
            .take(512)
        if (clean.isEmpty()) return null

        val vector = FloatArray(EMBEDDING_DIMENSION)
        for (token in clean) {
            val index = positiveHash(token) % EMBEDDING_DIMENSION
            vector[index] += 1f
        }

        var norm = 0f
        for (value in vector) norm += value * value
        val denom = sqrt(norm)
        if (denom == 0f) return null
        for (i in vector.indices) vector[i] /= denom
        return vector
    }

    override fun isInitialized() = initialized
    override fun getModelName()  = modelName
    override fun cleanup() {
        initialized = false
        modelName = "None"
    }

    @OptIn(ExperimentalForeignApi::class)
    private val modelsDir: String
        get() {
            val docsDir = NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null
            )
            val documentsPath = docsDir?.path ?: "${NSHomeDirectory()}/Documents"
            return "$documentsPath/models"
        }

    private fun preferredEmbeddingModelFile(): String? =
        EMBEDDING_MODEL_NAMES
            .map { "$modelsDir/$it" }
            .firstOrNull { NSFileManager.defaultManager.fileExistsAtPath(it) }

    private fun positiveHash(value: String): Int {
        var hash = 0
        for (ch in value) hash = (hash * 31) + ch.code
        return hash and Int.MAX_VALUE
    }

    private companion object {
        private const val EMBEDDING_DIMENSION = 512
        private val EMBEDDING_MODEL_NAMES = listOf(
            "embeddinggemma-300M_seq2048_mixed-precision.tflite",
            "embeddinggemma-300M_seq2048.tflite",
            "Gecko_512_quant.tflite",
            "gecko-110m-en-512.tflite",
            "gecko-110m-en-256.tflite",
            "gecko-110m-en-64.tflite",
            "gecko-110m-en-1024.tflite"
        )
    }
}
