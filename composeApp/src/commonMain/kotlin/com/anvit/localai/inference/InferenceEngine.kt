package com.anvit.localai.inference

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic inference interface.
 *
 * Android: backed by litertlm-android (JVM SDK).
 * iOS:     backed by the LiteRT-LM C API via Kotlin/Native cinterop.
 *
 * v1 iOS scope: CPU only, text-only, no image/audio.
 */
interface InferenceEngine {

    /**
     * Load the model from [modelPath] (absolute path to a .litertlm file).
     * [maxTokens] caps the KV-cache / context window.
     */
    fun load(modelPath: String, maxTokens: Int = 4096)

    /**
     * Stream tokens for a single-turn prompt.
     * Each emitted string is one raw token chunk (may be sub-word).
     * The flow completes when the engine signals `is_final`.
     */
    fun generateStream(prompt: String): Flow<String>

    /**
     * Stop an in-progress generation.
     * Safe to call even when no generation is running.
     */
    fun cancel()

    /**
     * Release all native resources. Safe to call multiple times.
     */
    fun unload()

    /** True once [load] has completed successfully. */
    val isLoaded: Boolean
}
