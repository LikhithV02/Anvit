package com.anvit.localai.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.anvit.localai.data.models.GemmaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class GemmaInferenceService(private val context: Context) {

    private var engine: Engine? = null
    private var currentModel: GemmaModel? = null
    private val engineMutex = Mutex()

    // Track when sessions are reset — used by ChatViewModel to decide
    // whether to send minimal context after a reload.
    private val sessionResetTimes = mutableMapOf<String, Long>()

    // Generation parameters
    private var topK: Int = 40
    private var topP: Float = 0.95f
    private var temperature: Float = 1.0f
    private var maxTokens: Int = 4000
    private var contextWindow: Int? = null   // null = use model default
    private var enableThinking: Boolean = true
    private var accelerator: String = "cpu"

    // Track which accelerator the currently loaded engine was built with,
    // so we reload when the user switches CPU ↔ GPU.
    private var loadedAccelerator: String = "cpu"

    // RAG tools injected from outside (null = no tools)
    private var ragTools: RagAgentTools? = null

    companion object {
        private const val TAG = "GemmaInference"
        const val SENTINEL_THINK = "\u200B\u200BTHINK\u200B\u200B"
        const val SENTINEL_ENDTHINK = "\u200B\u200BENDTHINK\u200B\u200B"
    }

    fun setRagTools(tools: RagAgentTools?) {
        ragTools = tools
        Log.d(TAG, if (tools != null) "RAG tools enabled" else "RAG tools disabled")
    }

    /** Called by the orchestrator before each generation to scope agent tool searches. */
    fun setActiveCollection(collectionId: String?) {
        ragTools?.collectionId = collectionId
    }

    fun setGenerationParams(
        topK: Int = 40,
        topP: Float = 0.95f,
        temperature: Float = 1.0f,
        enableThinking: Boolean = true,
        maxTokens: Int = 4000,
        contextWindow: Int? = null,
        accelerator: String = "cpu"
    ) {
        this.topK = topK
        this.topP = topP
        this.temperature = temperature
        this.enableThinking = enableThinking
        this.maxTokens = maxTokens
        this.contextWindow = contextWindow
        this.accelerator = accelerator
        Log.d(TAG, "Generation params: topK=$topK topP=$topP temp=$temperature " +
                "thinking=$enableThinking maxTokens=$maxTokens contextWindow=$contextWindow accelerator=$accelerator")
    }

    /**
     * Returns the effective max tokens to use for generation, respecting both
     * the user override and the model's context window ceiling.
     */
    fun getEffectiveMaxTokens(model: GemmaModel): Int {
        val window = contextWindow?.coerceIn(1, model.contextWindowSize) ?: model.contextWindowSize
        return maxTokens.coerceIn(1, window)
    }

    fun getCurrentModel(): GemmaModel? = currentModel
    fun isLoaded(): Boolean = engine != null && currentModel != null

    // ── Session tracking ──────────────────────────────────────────────────────

    fun recordSessionReset(chatId: String) {
        sessionResetTimes[chatId] = System.currentTimeMillis()
    }

    /** Returns true if the session for [chatId] was reset in the last 10 seconds. */
    fun wasSessionRecentlyReset(chatId: String): Boolean {
        val t = sessionResetTimes[chatId] ?: return false
        return (System.currentTimeMillis() - t) < 10_000
    }

    // ── Model loading ──────────────────────────────────────────────────────────

    suspend fun loadModel(model: GemmaModel): Boolean {
        return try {
            ensureEngineLoaded(model)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model ${model.displayName}: ${e.message}", e)
            false
        }
    }

    suspend fun unloadModel() {
        engineMutex.withLock {
            engine?.let {
                try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing engine: ${e.message}") }
            }
            engine = null
            currentModel = null
            Log.d(TAG, "Engine unloaded")
        }
    }

    private suspend fun ensureEngineLoaded(model: GemmaModel) {
        engineMutex.withLock {
            // Reload if model changed OR if the accelerator changed
            if (currentModel?.id == model.id && engine != null && loadedAccelerator == accelerator) return@withLock
            engine?.let {
                try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing old engine") }
            }
            engine = null

            val modelFile = withContext(Dispatchers.IO) {
                File(context.filesDir, "models/${model.fileName}").also {
                    if (!it.exists()) throw IllegalStateException(
                        "Model file not found: ${it.absolutePath}\n\nPlease download ${model.fileName} in Settings."
                    )
                }
            }

            val mainBackend = if (accelerator == "gpu") Backend.GPU() else Backend.CPU()
            val effectiveMaxTokens = getEffectiveMaxTokens(model)

            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = mainBackend,
                visionBackend = if (model.supportsVision) Backend.CPU() else null,
                audioBackend = if (model.supportsAudio) Backend.CPU() else null,
                maxNumTokens = effectiveMaxTokens,
                cacheDir = context.cacheDir.path
            )

            Log.d(TAG, "Loading engine for ${model.displayName} | " +
                    "vision=${model.supportsVision} audio=${model.supportsAudio} " +
                    "accelerator=$accelerator maxTokens=$effectiveMaxTokens")
            val newEngine = Engine(engineConfig)
            withContext(Dispatchers.IO) { newEngine.initialize() }
            engine = newEngine
            currentModel = model
            loadedAccelerator = accelerator
            Log.d(TAG, "Engine ready for ${model.displayName}")
        }
    }

    // ── Model type helpers ────────────────────────────────────────────────────

    /** True when the currently loaded model is a Gemma 4 variant. */
    private fun isGemma4Model(): Boolean =
        currentModel?.displayName?.contains("Gemma 4", ignoreCase = true) == true

    // ── ConversationConfig builders ───────────────────────────────────────────

    private fun buildConversationConfig(systemPrompt: String? = null): ConversationConfig {
        // Only inject the thinking token for Gemma 4 — earlier models don't support it.
        val sysInstruction = when {
            isGemma4Model() && enableThinking && systemPrompt != null ->
                Contents.of("<|think|>\n$systemPrompt")
            isGemma4Model() && enableThinking ->
                Contents.of("<|think|>")
            systemPrompt != null ->
                Contents.of(systemPrompt)
            else -> null
        }
        return ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = topK,
                topP = topP.toDouble(),
                temperature = temperature.toDouble()
            ),
            systemInstruction = sysInstruction
        )
    }

    @OptIn(ExperimentalApi::class)
    private fun buildAgentConversationConfig(tools: RagAgentTools, systemPrompt: String): ConversationConfig {
        val toolProviders: List<ToolProvider> = listOf(tool(tools))
        val sysText = if (isGemma4Model() && enableThinking) "<|think|>\n$systemPrompt" else systemPrompt
        return ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = topK,
                topP = topP.toDouble(),
                temperature = temperature.toDouble()
            ),
            systemInstruction = Contents.of(sysText),
            tools = toolProviders
        )
    }

    // ── Content builder ───────────────────────────────────────────────────────

    /**
     * Build a [Contents] payload from a text prompt with optional image and audio.
     *
     * Ordering follows the gallery approach: images first, then audio, then text.
     * Uses [Content.ImageFile] for images (more memory-efficient than ImageBytes).
     * Uses [Content.AudioBytes] for audio (raw bytes from a recorded audio file).
     */
    private fun buildContents(prompt: String, imagePath: String?, audioBytes: ByteArray? = null): Contents {
        val parts = mutableListOf<Content>()

        // Image (vision models only)
        if (imagePath != null && currentModel?.supportsVision == true) {
            try {
                val imageFile = File(imagePath)
                if (imageFile.exists()) {
                    parts.add(Content.ImageFile(imagePath))
                } else {
                    Log.w(TAG, "Image file not found at $imagePath — skipping image")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to attach image — skipping: ${e.message}")
            }
        }

        // Audio (audio-capable models only)
        if (audioBytes != null && currentModel?.supportsAudio == true) {
            parts.add(Content.AudioBytes(audioBytes))
        }

        // Text always last (gallery comment: "accurate last token")
        parts.add(Content.Text(prompt))

        return Contents.of(parts)
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Single-turn response used for agentic sub-tasks (routing, evaluation, critique).
     */
    suspend fun generateResponse(
        prompt: String,
        systemPrompt: String? = null,
        imagePath: String? = null,
        audioBytes: ByteArray? = null
    ): String {
        val eng = engine ?: throw IllegalStateException("No engine loaded. Call loadModel() first.")
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            val config = buildConversationConfig(systemPrompt)
            val contents = buildContents(prompt, imagePath, audioBytes)
            val effectiveMaxTokens = currentModel?.let { getEffectiveMaxTokens(it) } ?: maxTokens
            eng.createConversation(config).use { conv ->
                conv.sendMessageAsync(contents)
                    .take(effectiveMaxTokens)
                    .collect { msg ->
                        sb.append(
                            msg.contents.contents.filterIsInstance<Content.Text>()
                                .joinToString("") { it.text }
                        )
                    }
            }
            val (cleaned, _) = processStopTokens(sb.toString())
            cleaned
        }
    }

    /**
     * Streaming response for the chat UI, with optional RAG agent tools.
     *
     * Uses [channelFlow] + [withContext] instead of `flow{}.flowOn()` so that each
     * emitted token is delivered to the collector immediately without the 64-element
     * intermediate buffer that `flowOn` inserts. This gives true word-by-word streaming.
     */
    fun generateStream(
        prompt: String,
        systemPrompt: String,
        useAgentTools: Boolean = false,
        imagePath: String? = null,
        audioBytes: ByteArray? = null
    ): Flow<String> = channelFlow {
        val eng = engine ?: throw IllegalStateException("No engine loaded.")
        val localTools = if (useAgentTools) ragTools else null
        val effectiveMaxTokens = currentModel?.let { getEffectiveMaxTokens(it) } ?: maxTokens

        var thinkOpen  = false
        var thinkClose = false

        @OptIn(ExperimentalApi::class)
        val config = if (localTools != null) {
            ExperimentalFlags.enableConversationConstrainedDecoding = true
            val c = buildAgentConversationConfig(localTools, systemPrompt)
            ExperimentalFlags.enableConversationConstrainedDecoding = false
            c
        } else {
            buildConversationConfig(systemPrompt)
        }

        val contents = buildContents(prompt, imagePath, audioBytes)

        withContext(Dispatchers.IO) {
            eng.createConversation(config).use { conv ->
                conv.sendMessageAsync(contents)
                    .take(effectiveMaxTokens)
                    .collect { msg ->
                        val chunk = msg.contents.contents.filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        val thinkChunk = try { msg.channels?.get("thought") } catch (_: Exception) { null }
                        val isThinking = enableThinking && !thinkChunk.isNullOrEmpty()

                        if (isThinking) {
                            if (!thinkOpen) { send(SENTINEL_THINK); thinkOpen = true }
                            val (cleaned, _) = processStopTokens(thinkChunk!!)
                            if (cleaned.isNotEmpty()) send(cleaned)
                        } else {
                            if (thinkOpen && !thinkClose) { send(SENTINEL_ENDTHINK); thinkClose = true }
                            val (cleaned, _) = processStopTokens(chunk)
                            if (cleaned.isNotEmpty()) send(cleaned)
                        }
                    }
            }
        }

        if (thinkOpen && !thinkClose) send(SENTINEL_ENDTHINK)
    }

    // ── Stop-token processing ─────────────────────────────────────────────────

    /**
     * Strip LLM stop tokens from a text chunk.
     *
     * Returns a [Pair] of (cleaned text, shouldStop flag). The boolean is true when
     * a stop token was found — callers can use this to break out of a collection loop
     * early (though the LiteRT SDK's `.take(maxTokens)` already handles truncation).
     */
    private fun processStopTokens(text: String): Pair<String, Boolean> {
        val stopTokens = listOf("<|eot_id|>", "<|end_of_text|>", "<|end|>", "</s>")
        var cleaned = text
        var shouldStop = false
        for (token in stopTokens) {
            val idx = cleaned.indexOf(token)
            if (idx >= 0) {
                cleaned = cleaned.substring(0, idx)
                shouldStop = true
                break
            }
        }
        cleaned = cleaned
            .replace("<|start_header_id|>", "")
            .replace("<|end_header_id|>", "")
        return Pair(cleaned, shouldStop)
    }

    // ── Prompt utilities ──────────────────────────────────────────────────────

    /**
     * Extract only the latest user turn from a multi-turn conversation history string.
     * Useful for web-search intent detection and routing decisions that should only
     * look at the current query, not the full history.
     *
     * Format expected: "user: ...\nassistant: ...\nuser: ..."
     */
    fun extractCurrentUserMessage(prompt: String): String {
        val lines = prompt.trim().split('\n')
        for (i in lines.lastIndex downTo 0) {
            val line = lines[i].trim()
            if (line.startsWith("user:")) return line.removePrefix("user:").trim()
        }
        // No role prefix found — treat the entire prompt as the user message
        if (!prompt.contains("assistant:") && !prompt.contains("user:")) return prompt.trim()
        for (i in lines.lastIndex downTo 0) {
            val line = lines[i].trim()
            if (line.isNotEmpty() && !line.startsWith("assistant:")) return line
        }
        return prompt.trim()
    }
}
