package com.anvit.localai.inference

import com.anvit.localai.data.models.GemmaModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * iOS implementation of [InferenceService] backed by Cactus Compute.
 *
 * Cactus loads an unzipped model bundle directory and runs fully on-device.
 * Cloud handoff is disabled in every options JSON, guarded again by
 * CACTUS_DISABLE_CLOUD_HANDOFF from Swift startup, and asserted from the final
 * completion JSON before any response is accepted.
 */
class IosInferenceService : InferenceService {

    private val engine = CactusInferenceEngine()
    private var currentModel: GemmaModel? = null

    private val sessionResetTimes = mutableMapOf<String, Long>()

    private var topK: Int = 40
    private var topP: Float = 0.95f
    private var temperature: Float = 0.6f
    private var maxTokens: Int = 4000
    private var contextWindow: Int? = null
    private var enableThinking: Boolean = true
    private val defaultContextWindow = 8192

    private fun modelsDir(): String {
        val docsDir = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null
        )
        return (docsDir?.path ?: "") + "/models"
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
        this.maxTokens = maxTokens.coerceIn(1, IOS_MAX_OUTPUT_TOKENS)
        this.contextWindow = contextWindow?.coerceIn(1, IOS_CONTEXT_WINDOW_TOKENS)
        this.enableThinking = enableThinking
        println(
            "IosInferenceService: setGenerationParams backend=cactus thinking=$enableThinking " +
                "requestedMaxTokens=$maxTokens effectiveMaxTokens=${this.maxTokens} " +
                "requestedContextWindow=$contextWindow effectiveContextWindow=${this.contextWindow ?: defaultContextWindow}"
        )
    }

    override fun getEffectiveMaxTokens(model: GemmaModel): Int =
        (contextWindow ?: defaultContextWindow).coerceIn(1, minOf(model.contextWindowSize, IOS_CONTEXT_WINDOW_TOKENS))

    override fun getMaxOutputTokens(): Int = maxTokens

    override fun setActiveCollection(collectionId: String?) {
        // RAG retrieval is handled by AgenticRagOrchestrator in commonMain.
    }

    override suspend fun loadModel(model: GemmaModel): Boolean {
        return try {
            val modelDir = "${modelsDir()}/${model.fileName}"
            if (!NSFileManager.defaultManager.fileExistsAtPath("$modelDir/.complete")) {
                println("IosInferenceService: model not ready at $modelDir (.complete sentinel missing)")
                return false
            }
            val loadDirectory = resolveCactusModelDirectory(
                modelDirectory = modelDir,
                fileName = model.fileName,
                fileExists = NSFileManager.defaultManager::fileExistsAtPath
            )
            engine.load(loadDirectory, getEffectiveMaxTokens(model))
            currentModel = model
            println("IosInferenceService: loaded ${model.displayName} with Cactus")
            true
        } catch (e: Exception) {
            println("IosInferenceService: loadModel failed: ${e.message}")
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
        engine.reset()
    }

    override fun wasSessionRecentlyReset(chatId: String): Boolean {
        val t = sessionResetTimes[chatId] ?: return false
        return (currentTimeMillis() - t) < 10_000
    }

    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String?,
        allowThinking: Boolean,
        imagePath: String?,
        audioBytes: ByteArray?
    ): String {
        return try {
            engine.generateStream(
                messagesJson = buildCactusMessagesJson(
                    systemPrompt = systemPrompt,
                    userPrompt = prompt,
                    imagePath = imagePath,
                    requestThinking = enableThinking && allowThinking,
                ),
                optionsJson = buildOptions(allowThinking = allowThinking).toJson(),
                audioBytes = audioBytes
            ).fold("") { acc, token -> acc + token }
                .let(::stripThinkingMarkersFromText)
        } finally {
            engine.reset()
        }
    }

    override fun generateStream(
        prompt: String,
        systemPrompt: String,
        useAgentTools: Boolean,
        imagePath: String?,
        audioBytes: ByteArray?
    ): Flow<String> {
        println(
            "IosInferenceService: generateStream backend=cactus promptChars=${prompt.length}, " +
                "systemChars=${systemPrompt.length}, maxTokens=${getMaxOutputTokens()}, " +
                "thinking=$enableThinking"
        )
        val raw = engine.generateStream(
            messagesJson = buildCactusMessagesJson(
                systemPrompt = systemPrompt.ifBlank { null },
                userPrompt = prompt,
                imagePath = imagePath,
                requestThinking = enableThinking,
            ),
            optionsJson = buildOptions(allowThinking = enableThinking).toJson(),
            audioBytes = audioBytes
        )
        return if (enableThinking) raw.parseThinkingMarkers() else raw
    }

    private fun buildOptions(allowThinking: Boolean): CactusInferenceOptions =
        CactusInferenceOptions(
            temperature = temperature,
            topP = topP,
            topK = topK,
            maxTokens = getMaxOutputTokens(),
            stopSequences = listOf("<turn|>", "<end_of_turn>"),
            enableThinkingIfSupported = enableThinking && allowThinking
        )

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
    override fun stopInferenceForeground() { /* iOS: no foreground service needed */ }
    override fun stopGeneration() {
        engine.stop()
    }

    private fun currentTimeMillis(): Long = com.anvit.localai.utils.currentTimeMillis()

    private companion object {
        const val IOS_MAX_OUTPUT_TOKENS = 8192
        const val IOS_CONTEXT_WINDOW_TOKENS = 8192
    }
}
