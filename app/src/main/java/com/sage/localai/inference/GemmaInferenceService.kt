package com.sage.localai.inference

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.sage.localai.document.ImageAttachmentManager
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
import com.sage.localai.data.models.GemmaModel
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

    // Generation parameters
    private var topK: Int = 40
    private var topP: Float = 0.95f
    private var temperature: Float = 1.0f
    private var maxTokens: Int = 1024
    private var enableThinking: Boolean = true

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

    fun setGenerationParams(topK: Int = 40, topP: Float = 0.95f, temperature: Float = 1.0f, enableThinking: Boolean = true, maxTokens: Int = 1024) {
        this.topK = topK
        this.topP = topP
        this.temperature = temperature
        this.enableThinking = enableThinking
        this.maxTokens = maxTokens
    }

    fun getCurrentModel(): GemmaModel? = currentModel
    fun isLoaded(): Boolean = engine != null && currentModel != null

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
            if (currentModel?.id == model.id && engine != null) return@withLock
            engine?.let {
                try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing old engine") }
            }
            engine = null

            val modelFile = withContext(Dispatchers.IO) {
                File(context.filesDir, "models/${model.fileName}").also {
                    if (!it.exists()) throw IllegalStateException(
                        "Model file not found: ${it.absolutePath}\n\nPlease place ${model.fileName} in the app's models directory."
                    )
                }
            }

            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.path
            )

            Log.d(TAG, "Loading engine for ${model.displayName}")
            val newEngine = Engine(engineConfig)
            withContext(Dispatchers.IO) { newEngine.initialize() }
            engine = newEngine
            currentModel = model
            Log.d(TAG, "Engine ready for ${model.displayName}")
        }
    }

    private fun buildConversationConfig(systemPrompt: String? = null): ConversationConfig {
        val sysInstruction = when {
            enableThinking && systemPrompt != null -> Contents.of("<|think|>\n$systemPrompt")
            enableThinking -> Contents.of("<|think|>")
            systemPrompt != null -> Contents.of(systemPrompt)
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
        val sysText = if (enableThinking) "<|think|>\n$systemPrompt" else systemPrompt
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

    /**
     * Generate a single-turn response (used for agentic sub-tasks like routing, evaluation, critique).
     *
     * @param imagePath Optional path to an image file. When provided the image is encoded as a
     *                  base64 data tag and prepended to the prompt so vision-capable models can
     *                  reason over it.
     *                  TODO: Swap to Content.Image once LiteRT SDK ≥ 0.10.1 confirms the API.
     */
    suspend fun generateResponse(
        prompt: String,
        systemPrompt: String? = null,
        imagePath: String? = null
    ): String {
        val eng = engine ?: throw IllegalStateException("No engine loaded. Call loadModel() first.")
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            val config = buildConversationConfig(systemPrompt)
            val effectivePrompt = buildPromptWithImage(prompt, imagePath)
            eng.createConversation(config).use { conv ->
                var chunks = 0
                conv.sendMessageAsync(Contents.of(Content.Text(effectivePrompt)))
                    .take(maxTokens)
                    .collect { msg ->
                        chunks++
                        sb.append(
                            msg.contents.contents.filterIsInstance<Content.Text>()
                                .joinToString("") { it.text }
                        )
                    }
            }
            cleanStopTokens(sb.toString())
        }
    }

    /**
     * Generate a streaming response for the chat UI, with optional RAG tools.
     *
     * Uses [channelFlow] + [withContext] instead of `flow{}.flowOn()` so that each
     * emitted token is delivered to the collector immediately without the 64-element
     * intermediate buffer that `flowOn` inserts. This gives true word-by-word streaming
     * in the UI rather than batched delivery at the end.
     *
     * @param imagePath Optional path to an attached image. Encoded as base64 and prepended
     *                  to the prompt text so vision-capable models can see it.
     *                  TODO: Swap to Content.Image once LiteRT SDK ≥ 0.10.1 confirms the API.
     */
    fun generateStream(
        prompt: String,
        systemPrompt: String,
        useAgentTools: Boolean = false,
        imagePath: String? = null
    ): Flow<String> = channelFlow {
        val eng = engine ?: throw IllegalStateException("No engine loaded.")
        val localTools = if (useAgentTools) ragTools else null

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

        val effectivePrompt = buildPromptWithImage(prompt, imagePath)

        // Run the blocking inference on IO; channelFlow's send() is suspend-safe
        // across dispatcher boundaries — no extra buffer is inserted.
        withContext(Dispatchers.IO) {
            eng.createConversation(config).use { conv ->
                var chunks = 0
                conv.sendMessageAsync(Contents.of(Content.Text(effectivePrompt)))
                    .take(maxTokens)
                    .collect { msg ->
                        chunks++
                        val chunk = msg.contents.contents.filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        val thinkChunk = try { msg.channels?.get("thought") } catch (_: Exception) { null }
                        val isThinking = enableThinking && !thinkChunk.isNullOrEmpty()

                        if (isThinking) {
                            if (!thinkOpen) { send(SENTINEL_THINK); thinkOpen = true }
                            val cleaned = cleanStopTokens(thinkChunk!!)
                            if (cleaned.isNotEmpty()) send(cleaned)
                        } else {
                            if (thinkOpen && !thinkClose) { send(SENTINEL_ENDTHINK); thinkClose = true }
                            val cleaned = cleanStopTokens(chunk)
                            if (cleaned.isNotEmpty()) send(cleaned)
                        }
                    }
            }
        }

        if (thinkOpen && !thinkClose) send(SENTINEL_ENDTHINK)
    }


    /**
     * Prepend a base64-encoded image to the prompt when an image path is provided.
     *
     * This is the A3b fallback encoding. The model is expected to interpret the
     * `[image/jpeg;base64,<data>]` tag if it is vision-capable.
     *
     * Preferred path (A3a): once LiteRT SDK exposes Content.Image, replace this with
     * a multi-part Contents.of(Content.Image(bitmap), Content.Text(prompt)) call.
     */
    private fun buildPromptWithImage(prompt: String, imagePath: String?): String {
        if (imagePath == null) return prompt
        return try {
            val bitmap = ImageAttachmentManager.loadScaledBitmap(imagePath)
            val bytes  = ImageAttachmentManager.toJpegBytes(bitmap)
            val b64    = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "[image/jpeg;base64,$b64]\n$prompt"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to encode image $imagePath — sending text-only: ${e.message}")
            prompt
        }
    }

    private fun cleanStopTokens(text: String): String {
        val stopTokens = listOf("<|eot_id|>", "<|end_of_text|>", "<|end|>", "</s>")
        var cleaned = text
        for (token in stopTokens) {
            val idx = cleaned.indexOf(token)
            if (idx >= 0) { cleaned = cleaned.substring(0, idx); break }
        }
        return cleaned
            .replace("<|start_header_id|>", "")
            .replace("<|end_header_id|>", "")
    }
}
