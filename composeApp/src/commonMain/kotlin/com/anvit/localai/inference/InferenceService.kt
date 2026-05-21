package com.anvit.localai.inference

import com.anvit.localai.data.models.GemmaModel
import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic LLM inference interface.
 * Android: backed by GemmaInferenceService (litertlm-android JVM SDK)
 * iOS: backed by IosInferenceService (LiteRT-LM C API via cinterop)
 */
interface InferenceService {
    companion object {
        const val SENTINEL_THINK    = "\u200B\u200BTHINK\u200B\u200B"
        const val SENTINEL_ENDTHINK = "\u200B\u200BENDTHINK\u200B\u200B"
    }

    /**
     * Single-turn response for agentic sub-tasks (routing, evaluation, critique).
     * imagePath and audioBytes are platform-specific; pass null on iOS v1.
     */
    suspend fun generateResponse(
        prompt: String,
        systemPrompt: String? = null,
        allowThinking: Boolean = false,
        imagePath: String? = null,
        audioBytes: ByteArray? = null
    ): String

    /**
     * Streaming response for the chat UI.
     * Emits SENTINEL_THINK / SENTINEL_ENDTHINK around thinking blocks.
     */
    fun generateStream(
        prompt: String,
        systemPrompt: String,
        useAgentTools: Boolean = false,
        imagePath: String? = null,
        audioBytes: ByteArray? = null
    ): Flow<String>

    fun setGenerationParams(
        topK: Int = 40,
        topP: Float = 0.95f,
        temperature: Float = 1.0f,
        enableThinking: Boolean = true,
        maxTokens: Int = 4000,
        contextWindow: Int? = null,
        accelerator: String = "cpu"
    )

    /** Scopes agent tool document searches to the given collection. */
    fun setActiveCollection(collectionId: String?)

    suspend fun loadModel(model: GemmaModel): Boolean
    fun modelLoadFailureMessage(model: GemmaModel): String? = null
    suspend fun unloadModel()
    fun getCurrentModel(): GemmaModel?
    fun isLoaded(): Boolean

    fun getEffectiveMaxTokens(model: GemmaModel): Int
    fun getMaxOutputTokens(): Int = 4000

    fun recordSessionReset(chatId: String)
    fun wasSessionRecentlyReset(chatId: String): Boolean

    /**
     * Extracts the latest user turn from a multi-turn history string.
     * Format: "user: ...\nassistant: ...\nuser: ..."
     */
    fun extractCurrentUserMessage(prompt: String): String

    /** Start platform-specific foreground service to protect inference from being killed. */
    fun startInferenceForeground() {}

    /** Stop platform-specific foreground service. */
    fun stopInferenceForeground() {}

    /** Signals the platform inference engine to cancel any active generation. */
    fun stopGeneration() {}
}
