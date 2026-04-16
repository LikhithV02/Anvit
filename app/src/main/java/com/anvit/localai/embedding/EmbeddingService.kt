package com.anvit.localai.embedding

import android.content.Context
import android.util.Log
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Optional

interface EmbeddingService {
    suspend fun generateEmbedding(text: String): FloatArray?
    suspend fun initialize(): Boolean
    fun isInitialized(): Boolean
    fun cleanup()
    fun getModelName(): String
}

class GeckoEmbeddingService(private val context: Context) : EmbeddingService {

    private var geckoEmbedder: GeckoEmbeddingModel? = null
    private var initialized = false
    private val mutex = Mutex()
    private var modelName = "Unknown"

    companion object {
        private const val TAG = "GeckoEmbedding"
        // Known embedding model filenames (user downloads these to filesDir/models/)
        private val EMBEDDING_MODEL_NAMES = listOf(
            "embeddinggemma-300M_seq2048_mixed-precision.tflite",
            "embeddinggemma-300M_seq2048.tflite",
            "gecko-110m-en-512.tflite",
            "gecko-110m-en-256.tflite",
            "gecko-110m-en-64.tflite",
            "gecko-110m-en-1024.tflite"
        )
        private val TOKENIZER_NAMES = listOf("sentencepiece.model", "tokenizer.model")
    }

    override suspend fun initialize(): Boolean = mutex.withLock {
        if (initialized) return@withLock true
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

                Log.d(TAG, "Initializing embedding model: ${modelFile.name}")
                geckoEmbedder = GeckoEmbeddingModel(
                    modelFile.absolutePath,
                    if (tokenizerFile != null) Optional.of(tokenizerFile.absolutePath) else Optional.empty(),
                    true // GPU
                )
                // Test it
                val req = EmbeddingRequest.create(listOf(EmbedData.create("test", EmbedData.TaskType.RETRIEVAL_QUERY)))
                val result = geckoEmbedder!!.getEmbeddings(req).get()
                if (result.isNullOrEmpty()) {
                    geckoEmbedder = null
                    return@withContext false
                }
                modelName = modelFile.name
                initialized = true
                Log.d(TAG, "Embedding model ready. Dim=${result.size}, model=$modelName")
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
            val req = EmbeddingRequest.create(listOf(EmbedData.create(cleanText, EmbedData.TaskType.RETRIEVAL_QUERY)))
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
    }
}
