package com.anvit.localai.eval.services

import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.inference.InferenceService
import kotlinx.coroutines.flow.Flow

class GeminiInferenceService(
    private val client: GeminiClient
) : InferenceService {
    private var activeCollectionId: String? = null
    private var loaded = true

    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String?,
        imagePath: String?,
        audioBytes: ByteArray?
    ): String = client.generateText(prompt, systemPrompt)

    override fun generateStream(
        prompt: String,
        systemPrompt: String,
        useAgentTools: Boolean,
        imagePath: String?,
        audioBytes: ByteArray?
    ): Flow<String> = client.streamText(prompt, systemPrompt)

    override fun setGenerationParams(
        topK: Int,
        topP: Float,
        temperature: Float,
        enableThinking: Boolean,
        maxTokens: Int,
        contextWindow: Int?,
        accelerator: String
    ) = Unit

    override fun setActiveCollection(collectionId: String?) {
        activeCollectionId = collectionId
    }

    override suspend fun loadModel(model: GemmaModel): Boolean {
        loaded = true
        return true
    }

    override suspend fun unloadModel() {
        loaded = false
    }

    override fun getCurrentModel(): GemmaModel? = null

    override fun isLoaded(): Boolean = loaded

    override fun getEffectiveMaxTokens(model: GemmaModel): Int = model.contextWindowSize

    override fun recordSessionReset(chatId: String) = Unit

    override fun wasSessionRecentlyReset(chatId: String): Boolean = false

    override fun extractCurrentUserMessage(prompt: String): String =
        prompt.substringAfterLast("user:", prompt).trim()

    fun getActiveCollectionForDebug(): String? = activeCollectionId
}
