package com.anvit.localai.retrieval

data class RetrievedChunk(
    val chunkId: String,
    val docId: String,
    val fileName: String,
    val chunkIndex: Int,
    val content: String,
    val score: Float,
    val vectorScore: Float,
    val bm25Rank: Int
)
