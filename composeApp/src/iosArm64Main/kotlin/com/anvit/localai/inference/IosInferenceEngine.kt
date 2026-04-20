@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.anvit.localai.inference

import cnames.structs.LiteRtLmEngine
import com.anvit.litertlm.*
import kotlinx.cinterop.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * iOS implementation of [InferenceEngine] using the LiteRT-LM Conversation API.
 *
 * Design: a single Engine is loaded once (expensive). Each [generateStream] call
 * creates a *fresh* Conversation, sends the prompt (which the ViewModel has already
 * built to include full conversation history), streams the response, then destroys
 * the Conversation. This avoids context-window double-counting that would occur if
 * a stateful Conversation accumulated turns that the ViewModel has already embedded
 * in the prompt string.
 *
 * Threading: [LiteRtLmStreamCallback] fires on a C library background thread.
 * [Channel.trySend] is thread-safe; tokens are forwarded safely to the coroutine
 * collector.
 */
class IosInferenceEngine : InferenceEngine {

    private var engine: CPointer<LiteRtLmEngine>? = null

    // Sampler params applied to each fresh Conversation.
    private var topK: Int    = 40
    private var topP: Float  = 0.95f
    private var temp: Float  = 1.0f
    private var maxOutputTokens: Int = 4000

    // activeConv is only non-null while a generation is in flight (for cancel()).
    private var activeConv: kotlinx.cinterop.CPointer<cnames.structs.LiteRtLmConversation>? = null

    override val isLoaded: Boolean get() = engine != null

    // ── configure ─────────────────────────────────────────────────────────────

    fun setSamplerParams(topK: Int, topP: Float, temperature: Float, maxOutputTokens: Int) {
        this.topK = topK
        this.topP = topP
        this.temp = temperature
        this.maxOutputTokens = maxOutputTokens
    }

    // ── load / unload ──────────────────────────────────────────────────────────

    override fun load(modelPath: String, maxTokens: Int) {
        check(engine == null) { "IosInferenceEngine: already loaded — call unload() first." }

        val settings = litert_lm_engine_settings_create(
            model_path         = modelPath,
            backend_str        = "cpu",
            vision_backend_str = null,
            audio_backend_str  = null
        ) ?: error("LiteRtLm: engine_settings_create returned null for: $modelPath")

        litert_lm_engine_settings_set_max_num_tokens(settings, maxTokens)

        engine = litert_lm_engine_create(settings)
        litert_lm_engine_settings_delete(settings)
        engine ?: error("LiteRtLm: engine_create returned null — check model path/format.")
    }

    override fun unload() {
        activeConv?.let { litert_lm_conversation_cancel_process(it) }
        activeConv = null
        engine?.let { litert_lm_engine_delete(it) }
        engine = null
    }

    // ── cancel ─────────────────────────────────────────────────────────────────

    override fun cancel() {
        activeConv?.let { litert_lm_conversation_cancel_process(it) }
    }

    // ── generateStream ─────────────────────────────────────────────────────────

    /**
     * Creates a single-use Conversation with current sampler params, sends [prompt]
     * as a "user" message, streams tokens, then destroys the Conversation.
     *
     * Because the ViewModel embeds the full conversation history inside [prompt],
     * there is no need to retain state across calls.
     */
    override fun generateStream(prompt: String): Flow<String> = channelFlow {
        val eng = engine ?: error("IosInferenceEngine: call load() before generateStream().")

        // Build session config with current sampler params.
        val sessionConfig = litert_lm_session_config_create()
            ?: error("LiteRtLm: session_config_create returned null.")
        litert_lm_session_config_set_max_output_tokens(sessionConfig, maxOutputTokens)
        val sampler = nativeHeap.alloc<LiteRtLmSamplerParams>().apply {
            type        = kTopP
            top_k       = topK
            top_p       = topP
            temperature = temp
            seed        = 0
        }
        litert_lm_session_config_set_sampler_params(sessionConfig, sampler.ptr)
        nativeHeap.free(sampler)

        // Create a fresh, single-use Conversation.
        val convConfig = litert_lm_conversation_config_create(
            engine                      = eng,
            session_config              = sessionConfig,
            system_message_json         = null,
            tools_json                  = null,
            messages_json               = null,
            enable_constrained_decoding = false
        ) ?: run {
            litert_lm_session_config_delete(sessionConfig)
            error("LiteRtLm: conversation_config_create returned null.")
        }
        litert_lm_session_config_delete(sessionConfig)

        val conv = litert_lm_conversation_create(eng, convConfig)
        litert_lm_conversation_config_delete(convConfig)
        conv ?: error("LiteRtLm: conversation_create returned null.")

        activeConv = conv

        try {
            // Escape the prompt for embedding in JSON.
            val escaped = buildString {
                for (ch in prompt) when (ch) {
                    '\\' -> append("\\\\")
                    '"'  -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
            val messageJson = """{"role":"user","content":"$escaped"}"""

            val tokenChannel = Channel<Result<String>>(Channel.UNLIMITED)
            val stableRef    = StableRef.create(tokenChannel)

            val rc = litert_lm_conversation_send_message_stream(
                conversation  = conv,
                message_json  = messageJson,
                extra_context = null,
                callback      = staticCFunction { cbData, chunk, isFinal, errMsg ->
                    val ch = cbData!!.asStableRef<Channel<Result<String>>>().get()
                    when {
                        errMsg != null ->
                            ch.trySend(Result.failure(Exception(errMsg.toKString())))
                        chunk != null && chunk.toKString().isNotEmpty() ->
                            ch.trySend(Result.success(chunk.toKString()))
                    }
                    if (isFinal) ch.close()
                },
                callback_data = stableRef.asCPointer()
            )

            if (rc != 0) {
                stableRef.dispose()
                throw Exception("LiteRtLm: send_message_stream failed (code $rc)")
            }

            try {
                for (result in tokenChannel) send(result.getOrThrow())
            } finally {
                stableRef.dispose()
            }
        } finally {
            activeConv = null
            litert_lm_conversation_delete(conv)
        }
    }
}
