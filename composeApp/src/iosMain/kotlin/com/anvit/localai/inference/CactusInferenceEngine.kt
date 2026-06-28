package com.anvit.localai.inference

import com.cactus.CactusTokenCallback
import com.cactus.cactusComplete
import com.cactus.cactusDestroy
import com.cactus.cactusInit
import com.cactus.cactusReset
import com.cactus.cactusStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CactusInferenceEngine : InferenceEngine {

    private var handle: Long = 0L

    override val isLoaded: Boolean
        get() = handle != 0L

    override fun load(modelPath: String, maxTokens: Int) {
        unload()
        handle = cactusInit(modelPath, corpusDir = null, cacheIndex = false)
    }

    override fun unload() {
        val current = handle
        if (current != 0L) {
            cactusDestroy(current)
            handle = 0L
        }
    }

    override fun cancel() {
        stop()
    }

    fun stop() {
        val current = handle
        if (current != 0L) cactusStop(current)
    }

    fun reset() {
        val current = handle
        if (current != 0L) cactusReset(current)
    }

    override fun generateStream(prompt: String): Flow<String> =
        generateStream(
            messagesJson = buildCactusMessagesJson(systemPrompt = null, userPrompt = prompt),
            optionsJson = CactusInferenceOptions().toJson(),
            audioBytes = null
        )

    fun generateStream(
        messagesJson: String,
        optionsJson: String,
        audioBytes: ByteArray?
    ): Flow<String> = channelFlow {
        val current = handle
        if (current == 0L) throw IllegalStateException("Cactus model is not loaded")

        val tokenChannel = Channel<Result<String>>(Channel.UNLIMITED)
        var emittedStreamingToken = false

        val completion = launch(Dispatchers.Default) {
            try {
                val resultJson = cactusComplete(
                    handle = current,
                    messagesJson = messagesJson,
                    optionsJson = optionsJson,
                    toolsJson = null,
                    callback = CactusTokenCallback { token, _ ->
                        if (token.isNotEmpty()) {
                            emittedStreamingToken = true
                            tokenChannel.trySend(Result.success(token))
                        }
                    },
                    pcmData = audioBytes
                )
                val response = parseLocalOnlyResponse(resultJson)
                if (!emittedStreamingToken && response.isNotEmpty()) {
                    tokenChannel.trySend(Result.success(response))
                }
            } catch (e: Throwable) {
                tokenChannel.trySend(Result.failure(e))
            } finally {
                tokenChannel.close()
            }
        }

        try {
            for (result in tokenChannel) send(result.getOrThrow())
        } finally {
            completion.cancel()
        }
    }

    private fun parseLocalOnlyResponse(resultJson: String): String {
        val obj = Json.parseToJsonElement(resultJson).jsonObject
        val cloudHandoff = obj["cloud_handoff"]?.jsonPrimitive?.booleanOrNull ?: false
        check(!cloudHandoff) { "Cactus reported cloud_handoff=true; local-only inference aborted" }

        val success = obj["success"]?.jsonPrimitive?.booleanOrNull
        val error = obj["error"]?.jsonPrimitive?.contentOrNull
        if (success == false) throw IllegalStateException(error ?: "Cactus completion failed")
        return obj["response"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}
