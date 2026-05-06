package com.anvit.localai.eval.services

import com.anvit.localai.embedding.EmbeddingService

class GeminiEmbeddingService(
    private val client: GeminiClient
) : EmbeddingService {
    private var initialized = false

    override suspend fun generateEmbedding(text: String): FloatArray? = client.embed(text)

    override suspend fun initialize(): Boolean {
        initialized = true
        return true
    }

    override fun isInitialized(): Boolean = initialized

    override fun cleanup() {
        initialized = false
    }

    override fun getModelName(): String = "gemini-embedding-001"
}
