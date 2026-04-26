package com.anvit.localai.inference

import com.anvit.localai.data.models.GemmaModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.fold
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * iOS implementation of [InferenceService] backed by [IosInferenceEngine]
 * (mlx-swift-lm via C bridge with @_cdecl Swift symbols).
 *
 * Model format: MLX uses a directory of safetensors + config.json files.
 * [GemmaModel.fileName] is treated as the directory name inside Documents/models/.
 * Example: fileName = "gemma-3-1b-it-4bit" → Documents/models/gemma-3-1b-it-4bit/
 *
 * Key improvements over LiteRT-LM v1:
 *  - Metal GPU acceleration via MLX
 *  - Vision (image) input support via Gemma4 VLM
 *  - Stateful ChatSession with KV-cache reuse across turns
 *  - System prompt passed natively (not prepended to user message)
 */
class IosInferenceService : InferenceService {

    private val engine = IosInferenceEngine()
    private var currentModel: GemmaModel? = null

    private val sessionResetTimes = mutableMapOf<String, Long>()

    private var topK: Int = 40
    private var topP: Float = 0.95f
    private var temperature: Float = 0.6f
    private var maxTokens: Int = 4000
    private var contextWindow: Int? = null
    private var enableThinking: Boolean = true

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
        this.topK          = topK
        this.topP          = topP
        this.temperature   = temperature
        this.maxTokens     = maxTokens
        this.contextWindow = contextWindow
        this.enableThinking = enableThinking
        engine.setSamplerParams(
            topK            = topK,
            topP            = topP,
            temperature     = temperature,
            maxOutputTokens = getEffectiveMaxTokens(currentModel ?: return)
        )
    }

    override fun getEffectiveMaxTokens(model: GemmaModel): Int {
        val window = contextWindow?.coerceIn(1, model.contextWindowSize) ?: model.contextWindowSize
        return maxTokens.coerceIn(1, window)
    }

    override fun setActiveCollection(collectionId: String?) {
        // RAG retrieval is handled by AgenticRagOrchestrator in commonMain; no-op here.
    }

    override suspend fun loadModel(model: GemmaModel): Boolean {
        return try {
            // MLX model path is a directory, not a file.
            val modelDir = "${modelsDir()}/${model.fileName}"
            if (!NSFileManager.defaultManager.fileExistsAtPath("$modelDir/.complete")) {
                println("IosInferenceService: model not ready at $modelDir (.complete sentinel missing)")
                return false
            }
            engine.loadAsync(modelDir)
            engine.setSamplerParams(topK, topP, temperature, getEffectiveMaxTokens(model))
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
        // Clear the MLX ChatSession KV cache so the next turn starts fresh.
        engine.resetSession()
    }

    override fun wasSessionRecentlyReset(chatId: String): Boolean {
        val t = sessionResetTimes[chatId] ?: return false
        return (currentTimeMillis() - t) < 10_000
    }

    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String?,
        imagePath: String?,
        audioBytes: ByteArray?
    ): String {
        val effectiveSys = thinkingSystemPrompt(systemPrompt)
        return engine.generateStreamFull(prompt, effectiveSys, imagePath)
            .fold("") { acc, token -> acc + token }
            .stripThinkingMarkers()
    }

    override fun generateStream(
        prompt: String,
        systemPrompt: String,
        useAgentTools: Boolean,
        imagePath: String?,
        audioBytes: ByteArray?
    ): Flow<String> {
        val effectiveSys = thinkingSystemPrompt(systemPrompt.ifBlank { null })
        val raw = engine.generateStreamFull(prompt, effectiveSys, imagePath)
        return if (enableThinking) raw.parseThinkingMarkers() else raw
    }

    private fun thinkingSystemPrompt(base: String?): String? = when {
        !enableThinking          -> base
        base.isNullOrBlank()     -> "<|think|>"
        else                     -> "<|think|>\n$base"
    }

    // Gemma 4 wraps thinking in <|channel>thought\n...\n<channel|>.
    // Parses that out of the token stream and emits SENTINEL_THINK / SENTINEL_ENDTHINK.
    private fun Flow<String>.parseThinkingMarkers(): Flow<String> = channelFlow {
        val THINK_START = "<|channel>thought"
        val THINK_END   = "<channel|>"

        var buf       = ""
        var inThink   = false
        var thinkOpen = false

        collect { token ->
            buf += token

            // Keep looping while there are complete markers to consume
            var advanced = true
            while (advanced) {
                advanced = false

                if (!inThink) {
                    val idx = buf.indexOf(THINK_START)
                    if (idx >= 0) {
                        // Flush content before the start marker
                        if (idx > 0) send(buf.substring(0, idx))
                        if (!thinkOpen) { send(InferenceService.SENTINEL_THINK); thinkOpen = true }
                        inThink = true
                        buf = buf.substring(idx + THINK_START.length).trimStart('\n')
                        advanced = true
                    } else {
                        // Safe to flush everything except the last (marker.length - 1) chars
                        val safe = buf.length - (THINK_START.length - 1)
                        if (safe > 0) { send(buf.substring(0, safe)); buf = buf.substring(safe) }
                    }
                } else {
                    val idx = buf.indexOf(THINK_END)
                    if (idx >= 0) {
                        // Flush thinking content before the end marker
                        if (idx > 0) send(buf.substring(0, idx))
                        send(InferenceService.SENTINEL_ENDTHINK)
                        inThink = false
                        buf = buf.substring(idx + THINK_END.length).trimStart('\n')
                        advanced = true
                    } else {
                        val safe = buf.length - (THINK_END.length - 1)
                        if (safe > 0) { send(buf.substring(0, safe)); buf = buf.substring(safe) }
                    }
                }
            }
        }

        // Flush remaining buffer
        if (buf.isNotEmpty()) send(buf)
        if (thinkOpen && inThink) send(InferenceService.SENTINEL_ENDTHINK)
    }

    private fun String.stripThinkingMarkers(): String {
        val THINK_START = "<|channel>thought"
        val THINK_END   = "<channel|>"
        val sb = StringBuilder()
        var rest = this
        while (rest.isNotEmpty()) {
            val s = rest.indexOf(THINK_START)
            if (s < 0) { sb.append(rest); break }
            sb.append(rest.substring(0, s))
            val e = rest.indexOf(THINK_END, s)
            rest = if (e < 0) "" else rest.substring(e + THINK_END.length).trimStart('\n')
        }
        return sb.toString().trim()
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

    override fun startInferenceForeground() { /* iOS: no foreground service needed */ }
    override fun stopInferenceForeground()  { /* iOS: no foreground service needed */ }

    override fun stopGeneration() {
        engine.cancel()
    }

    private fun currentTimeMillis(): Long = com.anvit.localai.utils.currentTimeMillis()
}
