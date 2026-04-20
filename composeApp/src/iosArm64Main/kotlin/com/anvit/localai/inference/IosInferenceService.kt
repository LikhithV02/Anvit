package com.anvit.localai.inference

import com.anvit.localai.data.models.GemmaModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold
import platform.Foundation.NSFileManager
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask

/**
 * iOS implementation of [InferenceService] backed by [IosInferenceEngine]
 * (LiteRT-LM C API via Kotlin/Native cinterop).
 *
 * Limitations vs Android v1:
 *  - RAG agent tools: not wired (no ToolSet equivalent on iOS v1)
 *  - Vision/audio: not implemented (v2)
 *  - GPU acceleration: not yet available upstream (v2)
 *  - System prompt is prepended to the user message in the conversation
 *    (LiteRT-LM Conversation API accepts a system_message_json in the
 *     ConversationConfig, but for v1 we keep the engine construction simple)
 */
class IosInferenceService : InferenceService {

    private val engine = IosInferenceEngine()
    private var currentModel: GemmaModel? = null

    private val sessionResetTimes = mutableMapOf<String, Long>()

    private var topK: Int = 40
    private var topP: Float = 0.95f
    private var temperature: Float = 1.0f
    private var maxTokens: Int = 4000
    private var contextWindow: Int? = null

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun modelsDir(): String {
        val docsDir = NSFileManager.defaultManager.URLForDirectory(
            directory         = NSDocumentDirectory,
            inDomain          = NSUserDomainMask,
            appropriateForURL = null,
            create            = true,
            error             = null
        )
        return (docsDir?.path ?: "") + "/models"
    }

    // ── InferenceService ──────────────────────────────────────────────────────

    override fun setGenerationParams(
        topK: Int, topP: Float, temperature: Float,
        enableThinking: Boolean, maxTokens: Int,
        contextWindow: Int?, accelerator: String
    ) {
        this.topK = topK
        this.topP = topP
        this.temperature = temperature
        this.maxTokens = maxTokens
        this.contextWindow = contextWindow
        engine.setSamplerParams(
            topK           = topK,
            topP           = topP,
            temperature    = temperature,
            maxOutputTokens = getEffectiveMaxTokens(currentModel ?: return)
        )
    }

    override fun getEffectiveMaxTokens(model: GemmaModel): Int {
        val window = contextWindow?.coerceIn(1, model.contextWindowSize) ?: model.contextWindowSize
        return maxTokens.coerceIn(1, window)
    }

    override fun setActiveCollection(collectionId: String?) {
        // RAG agent tools not wired on iOS v1 — no-op
    }

    override suspend fun loadModel(model: GemmaModel): Boolean {
        return try {
            if (engine.isLoaded) engine.unload()
            val modelPath = "${modelsDir()}/${model.fileName}"
            if (!NSFileManager.defaultManager.fileExistsAtPath(modelPath)) {
                println("IosInferenceService: model file not found at $modelPath")
                return false
            }
            val effectiveMaxOut = getEffectiveMaxTokens(model)
            engine.load(modelPath, model.contextWindowSize)
            engine.setSamplerParams(topK, topP, temperature, effectiveMaxOut)
            currentModel = model
            println("IosInferenceService: loaded ${model.displayName}")
            true
        } catch (e: Exception) {
            println("IosInferenceService: loadModel failed — ${e.message}")
            false
        }
    }

    override suspend fun unloadModel() {
        engine.unload()
        currentModel = null
    }

    override fun getCurrentModel(): GemmaModel? = currentModel
    override fun isLoaded(): Boolean = engine.isLoaded

    override fun recordSessionReset(chatId: String) {
        sessionResetTimes[chatId] = currentTimeMillis()
    }

    override fun wasSessionRecentlyReset(chatId: String): Boolean {
        val t = sessionResetTimes[chatId] ?: return false
        return (currentTimeMillis() - t) < 10_000
    }

    /** Build a single prompt string that includes the system instruction. */
    private fun buildPrompt(prompt: String, systemPrompt: String?): String =
        if (systemPrompt.isNullOrBlank()) prompt
        else "System: $systemPrompt\n\n$prompt"

    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String?,
        imagePath: String?,
        audioBytes: ByteArray?
    ): String {
        val fullPrompt = buildPrompt(prompt, systemPrompt)
        return engine.generateStream(fullPrompt).fold("") { acc, token -> acc + token }
    }

    override fun generateStream(
        prompt: String,
        systemPrompt: String,
        useAgentTools: Boolean,
        imagePath: String?,
        audioBytes: ByteArray?
    ): Flow<String> {
        val fullPrompt = buildPrompt(prompt, systemPrompt)
        return engine.generateStream(fullPrompt)
    }

    override fun extractCurrentUserMessage(prompt: String): String {
        val lines = prompt.trim().split('\n')
        for (i in lines.lastIndex downTo 0) {
            val line = lines[i].trim()
            if (line.startsWith("user:")) return line.removePrefix("user:").trim()
        }
        if (!prompt.contains("assistant:") && !prompt.contains("user:")) return prompt.trim()
        for (i in lines.lastIndex downTo 0) {
            val line = lines[i].trim()
            if (line.isNotEmpty() && !line.startsWith("assistant:")) return line
        }
        return prompt.trim()
    }

    override fun startInferenceForeground() {
        // No-op for iOS v1.
    }

    override fun stopInferenceForeground() {
        // No-op for iOS v1.
    }

    private fun currentTimeMillis(): Long {
        return com.anvit.localai.utils.currentTimeMillis()
    }
}
