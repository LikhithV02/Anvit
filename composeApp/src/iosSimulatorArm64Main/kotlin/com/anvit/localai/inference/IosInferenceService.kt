package com.anvit.localai.inference

import com.anvit.localai.data.models.GemmaModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * iOS Simulator stub for InferenceService.
 *
 * LiteRT-LM ships arm64 device-only native libraries in v1.
 * Running inference on the Simulator is blocked until upstream adds Simulator slices.
 * The UI still renders and non-inference features (RAG ingestion, collections) work normally.
 *
 * v2: once upstream ships Simulator XCFramework slices, replace this with the full
 * IosInferenceService (or a unified source set via cinterop for both arm64 + simulator).
 */
class IosInferenceService : InferenceService {

    private val stubMessage =
        "[Simulator stub] MLX inference is not available in the iOS Simulator. " +
        "Run on a physical iPhone (arm64) to use AI features."

    override suspend fun loadModel(model: GemmaModel): Boolean = false

    override fun modelLoadFailureMessage(model: GemmaModel): String =
        "MLX inference is not available in the iOS Simulator. Run Anvit on a physical iPhone to load ${model.displayName}."

    override fun isLoaded(): Boolean = false

    override fun getCurrentModel(): GemmaModel? = null

    override suspend fun unloadModel() { /* no-op */ }

    override fun generateStream(
        prompt: String,
        systemPrompt: String,
        useAgentTools: Boolean,
        imagePath: String?,
        audioBytes: ByteArray?
    ): Flow<String> = flow {
        emit(stubMessage)
    }

    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String?,
        allowThinking: Boolean,
        imagePath: String?,
        audioBytes: ByteArray?
    ): String = stubMessage

    override fun setGenerationParams(
        topK: Int,
        topP: Float,
        temperature: Float,
        enableThinking: Boolean,
        maxTokens: Int,
        contextWindow: Int?,
        accelerator: String
    ) { /* no-op */ }

    override fun setActiveCollection(collectionId: String?) { /* no-op */ }

    override fun getEffectiveMaxTokens(model: GemmaModel): Int = 8192

    override fun getMaxOutputTokens(): Int = 4000

    override fun recordSessionReset(chatId: String) { /* no-op */ }

    override fun wasSessionRecentlyReset(chatId: String): Boolean = false

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

    override fun startInferenceForeground() { /* no-op */ }
    override fun stopInferenceForeground() { /* no-op */ }
    override fun stopGeneration() { /* no-op */ }
}
