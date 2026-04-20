package com.anvit.localai.embedding

interface EmbeddingService {
    suspend fun generateEmbedding(text: String): FloatArray?
    suspend fun initialize(): Boolean
    fun isInitialized(): Boolean
    fun cleanup()
    fun getModelName(): String
}
