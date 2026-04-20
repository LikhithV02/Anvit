package com.anvit.localai.embedding

/**
 * iOS stub implementation of [EmbeddingService] for v1.
 *
 * v2 will replace this with MediaPipe Tasks Text Embedder (iOS CocoaPod).
 * The v1 stub returns null embeddings — RAG retrieval falls back to
 * BM25 lexical search only when embeddings are unavailable.
 */
class IosEmbeddingService : EmbeddingService {
    private var initialized = false

    override suspend fun initialize(): Boolean {
        println("IosEmbeddingService: v1 stub — no embedding model on iOS yet")
        initialized = true
        return false   // embeddings unavailable
    }

    override suspend fun generateEmbedding(text: String): FloatArray? = null

    override fun isInitialized() = initialized
    override fun getModelName()  = "None (iOS v1 stub)"
    override fun cleanup()       { initialized = false }
}
