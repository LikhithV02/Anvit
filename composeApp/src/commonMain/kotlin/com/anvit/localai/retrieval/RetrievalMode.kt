package com.anvit.localai.retrieval

enum class RetrievalMode {
    VECTOR,
    BM25,
    HYBRID;

    companion object {
        fun fromPreference(value: String?): RetrievalMode =
            when (value?.lowercase()) {
                "vector" -> VECTOR
                "bm25" -> BM25
                else -> HYBRID
            }
    }
}
