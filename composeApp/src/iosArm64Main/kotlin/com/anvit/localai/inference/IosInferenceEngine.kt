@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.anvit.localai.inference

import com.anvit.mlxbridge.*
import kotlinx.cinterop.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * iOS inference engine backed by mlx-swift-lm (Gemma4 / VLM).
 *
 * The Swift bridge exports C symbols via @_cdecl. Each call passes a StableRef
 * as user_data so the C-compatible staticCFunction can route back to Kotlin state
 * without capturing closures.
 *
 * Threading: MLX callbacks fire from Swift's concurrency thread pool.
 * Channel.trySend is thread-safe; tokens are forwarded to the coroutine collector.
 *
 * Note: [load] (from InferenceEngine interface) is not used on iOS — IosInferenceService
 * calls [loadAsync] directly since model loading is genuinely async on MLX.
 */
class IosInferenceEngine : InferenceEngine {

    private var topK:             Int   = 40
    private var topP:             Float = 0.95f
    private var temp:             Float = 0.6f
    private var maxOutputTokens:  Int   = 4000

    override val isLoaded: Boolean get() = mlx_engine_is_loaded()

    fun setSamplerParams(topK: Int, topP: Float, temperature: Float, maxOutputTokens: Int) {
        this.topK           = topK
        this.topP           = topP
        this.temp           = temperature
        this.maxOutputTokens = maxOutputTokens
    }

    // ── load / unload ──────────────────────────────────────────────────────────

    /**
     * Not used on iOS — call [loadAsync] from a suspend context instead.
     * The interface method exists for the Android implementation.
     */
    override fun load(modelPath: String, maxTokens: Int) {
        error("Call loadAsync() on iOS instead of load()")
    }

    /**
     * Load the MLX model directory at [modelPath].
     * Bridges the async C callback to a Kotlin Channel so callers can suspend.
     */
    suspend fun loadAsync(modelPath: String) {
        if (mlx_engine_is_loaded()) mlx_engine_unload()

        val resultChannel = Channel<Result<Unit>>(1)
        val stableRef = StableRef.create(resultChannel)

        mlx_engine_load(
            model_dir_path = modelPath,
            user_data      = stableRef.asCPointer(),
            callback       = staticCFunction { userData, success, errMsg ->
                val ch = userData!!.asStableRef<Channel<Result<Unit>>>().get()
                if (success) {
                    ch.trySend(Result.success(Unit))
                } else {
                    val msg = errMsg?.toKString() ?: "mlx_engine_load failed"
                    ch.trySend(Result.failure(Exception(msg)))
                }
                ch.close()
            }
        )

        try {
            resultChannel.receive().getOrThrow()
        } finally {
            stableRef.dispose()
        }
    }

    override fun unload() {
        mlx_engine_unload()
    }

    override fun cancel() {
        mlx_engine_cancel()
    }

    fun resetSession() {
        mlx_engine_reset_session()
    }

    // ── generateStream ─────────────────────────────────────────────────────────

    /** Single-turn text-only streaming (satisfies InferenceEngine interface). */
    override fun generateStream(prompt: String): Flow<String> =
        generateStreamFull(prompt, null, null)

    /**
     * Full streaming call with optional system prompt and image.
     * Each emitted string is one raw token chunk.
     */
    fun generateStreamFull(
        prompt: String,
        systemPrompt: String?,
        imagePath: String?
    ): Flow<String> = channelFlow {
        val tokenChannel = Channel<Result<String>>(Channel.UNLIMITED)
        val stableRef = StableRef.create(tokenChannel)

        mlx_engine_generate_stream(
            prompt         = prompt,
            system_prompt  = systemPrompt,
            image_path     = imagePath,
            temperature    = temp,
            top_k          = topK,
            top_p          = topP,
            max_tokens     = maxOutputTokens,
            user_data      = stableRef.asCPointer(),
            token_callback = staticCFunction { userData, token, isFinal, errMsg ->
                val ch = userData!!.asStableRef<Channel<Result<String>>>().get()
                when {
                    errMsg != null ->
                        ch.trySend(Result.failure(Exception(errMsg.toKString())))
                    token != null && token.toKString().isNotEmpty() ->
                        ch.trySend(Result.success(token.toKString()))
                }
                if (isFinal) ch.close()
            }
        )

        try {
            for (result in tokenChannel) send(result.getOrThrow())
        } finally {
            stableRef.dispose()
        }
    }
}
