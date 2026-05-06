package com.anvit.localai.embedding

import android.content.Context
import android.util.Log
import com.anvit.localai.data.preferences.AnvitPreferences
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Optional

/**
 * Android implementation of [EmbeddingService] backed by the GeckoEmbeddingModel
 * from the Google AI Edge localagents-rag library.
 */
class GeckoEmbeddingService(
    private val context: Context,
    private val preferences: AnvitPreferences
) : EmbeddingService {

    private var geckoEmbedder: GeckoEmbeddingModel? = null
    private var initialized = false
    private val mutex = Mutex()
    private var modelName = "Unknown"
    private var initializedWithGpu = false

    companion object {
        private const val TAG = "GeckoEmbedding"
        private val EMBEDDING_MODEL_NAMES = listOf(
            "embeddinggemma-300M_seq2048_mixed-precision.tflite",
            "embeddinggemma-300M_seq2048.tflite",
            "Gecko_512_quant.tflite",
            "gecko-110m-en-512.tflite",
            "gecko-110m-en-256.tflite",
            "gecko-110m-en-64.tflite",
            "gecko-110m-en-1024.tflite"
        )
        private val TOKENIZER_NAMES = listOf("sentencepiece.model", "tokenizer.model")
    }

    override suspend fun initialize(): Boolean = mutex.withLock {
        val useGpu = preferences.accelerator.first() == "gpu"
        if (initialized && initializedWithGpu == useGpu) return@withLock true
        // Re-initialize if accelerator setting changed
        if (initialized) {
            geckoEmbedder = null
            initialized = false
        }
        withContext(Dispatchers.IO) {
            try {
                val modelsDir = File(context.filesDir, "models")
                val modelFile = EMBEDDING_MODEL_NAMES.map { File(modelsDir, it) }.firstOrNull { it.exists() }
                    ?: run {
                        Log.e(TAG, "No embedding model found in ${modelsDir.absolutePath}")
                        Log.e(TAG, "Expected one of: ${EMBEDDING_MODEL_NAMES.joinToString()}")
                        return@withContext false
                    }
                val tokenizerFile = TOKENIZER_NAMES.map { File(modelsDir, it) }.firstOrNull { it.exists() }

                Log.d(TAG, "Initializing embedding model: ${modelFile.name} (GPU=$useGpu)")
                geckoEmbedder = GeckoEmbeddingModel(
                    modelFile.absolutePath,
                    if (tokenizerFile != null) Optional.of(tokenizerFile.absolutePath) else Optional.empty(),
                    useGpu
                )
                // Warm-up check
                val req = EmbeddingRequest.create(
                    listOf(EmbedData.create("test", EmbedData.TaskType.RETRIEVAL_QUERY))
                )
                val result = geckoEmbedder!!.getEmbeddings(req).get()
                if (result.isNullOrEmpty()) {
                    geckoEmbedder = null
                    return@withContext false
                }
                modelName = modelFile.name
                initialized = true
                initializedWithGpu = useGpu
                Log.d(TAG, "Embedding model ready. Dim=${result.size}, model=$modelName, GPU=$useGpu")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Embedding model init failed: ${e.message}", e)
                geckoEmbedder = null
                false
            }
        }
    }

    override suspend fun generateEmbedding(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (!initialized && !initialize()) return@withContext null
        try {
            val cleanText = text.trim().take(1024)
            if (cleanText.isEmpty()) return@withContext null
            val req = EmbeddingRequest.create(
                listOf(EmbedData.create(cleanText, EmbedData.TaskType.RETRIEVAL_QUERY))
            )
            geckoEmbedder?.getEmbeddings(req)?.get()?.toFloatArray()
        } catch (e: Exception) {
            Log.e(TAG, "generateEmbedding failed: ${e.message}")
            null
        }
    }

    override fun isInitialized() = initialized
    override fun getModelName() = modelName

    override fun cleanup() {
        geckoEmbedder = null
        initialized = false
        initializedWithGpu = false
    }
}
