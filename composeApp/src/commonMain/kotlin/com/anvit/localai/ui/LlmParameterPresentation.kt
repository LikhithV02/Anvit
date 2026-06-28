package com.anvit.localai.ui

data class LlmParameterDefaults(
    val temperature: Float,
    val topK: Int,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val maxRetrievalChunks: Int,
    val retrievalMode: String,
)

const val IOS_CACTUS_CONTEXT_WINDOW = 8192
const val DEFAULT_MAX_OUTPUT_TOKENS = 4000
const val DEFAULT_TOP_K = 40
const val DEFAULT_MAX_RETRIEVAL_CHUNKS = 5

fun llmParameterDefaults(isIos: Boolean): LlmParameterDefaults =
    LlmParameterDefaults(
        temperature = if (isIos) 0.6f else 1.0f,
        topK = DEFAULT_TOP_K,
        contextWindow = IOS_CACTUS_CONTEXT_WINDOW,
        maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
        maxRetrievalChunks = DEFAULT_MAX_RETRIEVAL_CHUNKS,
        retrievalMode = if (isIos) "bm25" else "hybrid",
    )

fun contextWindowSettingMax(isIos: Boolean, modelContextWindow: Int): Int =
    if (isIos) minOf(IOS_CACTUS_CONTEXT_WINDOW, modelContextWindow) else modelContextWindow
