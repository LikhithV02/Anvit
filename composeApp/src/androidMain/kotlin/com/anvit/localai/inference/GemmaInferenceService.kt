package com.anvit.localai.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
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

/**
 * Android implementation of [InferenceService] backed by the LiteRT-LM JVM SDK.
 */
class GemmaInferenceService(private val context: Context) : InferenceService {

    private var engine: Engine? = null
    private var currentModel: GemmaModel? = null
    private val engineMutex = Mutex()

    private val sessionResetTimes = mutableMapOf<String, Long>()

    @Volatile
    private var activeConversation: Conversation? = null

    private var topK: Int = 40
    private var topP: Float = 0.95f
    private var temperature: Float = 1.0f
    private var maxTokens: Int = 4000
    private var contextWindow: Int? = null
    private var enableThinking: Boolean = true
    private var accelerator: String = "cpu"
    private var loadedAccelerator: String = "cpu"
    private var loadedContextWindow: Int? = null

    private var ragTools: RagAgentTools? = null

    companion object {
        private const val TAG = "GemmaInference"
        private const val DEFAULT_CONTEXT_WINDOW = 8192
    }

    fun setRagTools(tools: RagAgentTools?) {
        ragTools = tools
        Log.d(TAG, if (tools != null) "RAG tools enabled" else "RAG tools disabled")
    }

    override fun setActiveCollection(collectionId: String?) {
        ragTools?.collectionId = collectionId
    }

    override fun setGenerationParams(
        topK: Int,
        topP: Float,
        temperature: Float,
        enableThinking: Boolean,
        maxTokens: Int,
        contextWindow: Int?,
        accelerator: String
    ) {
        this.topK = topK
        this.topP = topP
        this.temperature = temperature
        this.enableThinking = enableThinking
        this.maxTokens = maxTokens
        this.contextWindow = contextWindow
        this.accelerator = accelerator
        Log.d(TAG, "Generation params: topK=$topK topP=$topP temp=$temperature " +
                "thinking=$enableThinking maxOutputTokens=$maxTokens contextWindow=$contextWindow accelerator=$accelerator")
    }

    override fun getEffectiveMaxTokens(model: GemmaModel): Int {
        return (contextWindow ?: DEFAULT_CONTEXT_WINDOW).coerceIn(1, model.contextWindowSize)
    }

    override fun getMaxOutputTokens(): Int = maxTokens

    override fun getCurrentModel(): GemmaModel? = currentModel
    override fun isLoaded(): Boolean = engine != null && currentModel != null

    override fun recordSessionReset(chatId: String) {
        sessionResetTimes[chatId] = System.currentTimeMillis()
    }

    override fun wasSessionRecentlyReset(chatId: String): Boolean {
        val t = sessionResetTimes[chatId] ?: return false
        return (System.currentTimeMillis() - t) < 10_000
    }

    override suspend fun loadModel(model: GemmaModel): Boolean {
        return try {
            ensureEngineLoaded(model)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model ${model.displayName}: ${e.message}", e)
            false
        }
    }

    /** Like [loadModel] but throws the underlying exception instead of returning false. */
    suspend fun loadModelOrThrow(model: GemmaModel) = ensureEngineLoaded(model)

    override suspend fun unloadModel() {
        engineMutex.withLock {
            engine?.let {
                try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing engine: ${e.message}") }
            }
            engine = null
            currentModel = null
            loadedContextWindow = null
            Log.d(TAG, "Engine unloaded")
        }
    }

    private suspend fun ensureEngineLoaded(model: GemmaModel) {
        engineMutex.withLock {
            val requestedContextWindow = getEffectiveMaxTokens(model)
            if (
                currentModel?.id == model.id &&
                engine != null &&
                loadedAccelerator == accelerator &&
                loadedContextWindow == requestedContextWindow
            ) return@withLock
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
            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = mainBackend,
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = requestedContextWindow,
                cacheDir = context.cacheDir.path
            )

            Log.d(TAG, "Loading engine for ${model.displayName} | " +
                    "accelerator=$accelerator contextWindow=$requestedContextWindow maxOutputTokens=$maxTokens")
            val newEngine = Engine(engineConfig)
            withContext(Dispatchers.IO) { newEngine.initialize() }
            engine = newEngine
            currentModel = model
            loadedAccelerator = accelerator
            loadedContextWindow = requestedContextWindow
            Log.d(TAG, "Engine ready for ${model.displayName}")
        }
    }

    private fun isGemma4Model(): Boolean =
        currentModel?.displayName?.contains("Gemma 4", ignoreCase = true) == true

    private fun buildConversationConfig(
        systemPrompt: String? = null,
        allowThinking: Boolean = enableThinking
    ): ConversationConfig {
        val sysInstruction = when {
            isGemma4Model() && allowThinking && systemPrompt != null ->
                Contents.of("<|think|>\n$systemPrompt")
            isGemma4Model() && allowThinking ->
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

    private fun buildContents(prompt: String, imagePath: String?, audioBytes: ByteArray? = null): Contents {
        val parts = mutableListOf<Content>()

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

        if (audioBytes != null && currentModel?.supportsAudio == true) {
            parts.add(Content.AudioBytes(audioBytes))
        }

        parts.add(Content.Text(prompt))
        return Contents.of(parts)
    }

    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String?,
        allowThinking: Boolean,
        imagePath: String?,
        audioBytes: ByteArray?
    ): String {
        val eng = engine ?: throw IllegalStateException("No engine loaded. Call loadModel() first.")
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            val config = buildConversationConfig(systemPrompt, enableThinking && allowThinking)
            val contents = buildContents(prompt, imagePath, audioBytes)
            val effectiveMaxTokens = currentModel?.let { maxTokens.coerceIn(1, getEffectiveMaxTokens(it)) } ?: maxTokens
            eng.createConversation(config).also { activeConversation = it }.use { conv ->
                try {
                    conv.sendMessageAsync(contents)
                        .take(effectiveMaxTokens)
                        .collect { msg ->
                            sb.append(
                                msg.contents.contents.filterIsInstance<Content.Text>()
                                    .joinToString("") { it.text }
                            )
                        }
                } finally {
                    activeConversation = null
                }
            }
            val (cleaned, _) = processStopTokens(sb.toString())
            cleaned
        }
    }

    override fun generateStream(
        prompt: String,
        systemPrompt: String,
        useAgentTools: Boolean,
        imagePath: String?,
        audioBytes: ByteArray?
    ): Flow<String> = channelFlow {
        val eng = engine ?: throw IllegalStateException("No engine loaded.")
        val localTools = if (useAgentTools) ragTools else null
        val effectiveMaxTokens = currentModel?.let { maxTokens.coerceIn(1, getEffectiveMaxTokens(it)) } ?: maxTokens

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
            eng.createConversation(config).also { activeConversation = it }.use { conv ->
                try {
                    conv.sendMessageAsync(contents)
                        .take(effectiveMaxTokens)
                        .collect { msg ->
                            val chunk = msg.contents.contents.filterIsInstance<Content.Text>()
                                .joinToString("") { it.text }
                            val thinkChunk = try { msg.channels?.get("thought") } catch (_: Exception) { null }
                            val isThinking = enableThinking && !thinkChunk.isNullOrEmpty()

                            if (isThinking) {
                                if (!thinkOpen) { send(InferenceService.SENTINEL_THINK); thinkOpen = true }
                                val (cleaned, _) = processStopTokens(thinkChunk!!)
                                if (cleaned.isNotEmpty()) send(cleaned)
                            } else {
                                if (thinkOpen && !thinkClose) { send(InferenceService.SENTINEL_ENDTHINK); thinkClose = true }
                                val (cleaned, _) = processStopTokens(chunk)
                                if (cleaned.isNotEmpty()) send(cleaned)
                            }
                        }
                } finally {
                    activeConversation = null
                }
            }
        }

        if (thinkOpen && !thinkClose) send(InferenceService.SENTINEL_ENDTHINK)
    }

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
        try { InferenceForegroundService.start(context) }
        catch (e: Throwable) { Log.w(TAG, "startInferenceForeground swallowed: ${e.message}") }
    }

    override fun stopInferenceForeground() {
        try { InferenceForegroundService.stop(context) }
        catch (e: Throwable) { Log.w(TAG, "stopInferenceForeground swallowed: ${e.message}") }
    }

    override fun stopGeneration() {
        try {
            activeConversation?.cancelProcess()
            Log.d(TAG, "Canceled active conversation process")
        } catch (e: Exception) {
            Log.w(TAG, "stopGeneration failed: ${e.message}")
        }
    }
}
