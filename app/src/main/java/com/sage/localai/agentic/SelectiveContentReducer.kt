package com.sage.localai.agentic

import com.sage.localai.retrieval.RetrievedChunk

/**
 * Trims retrieved chunks to the most relevant sentences before LLM generation.
 * Reduces token usage and latency (based on MobileRAG paper: ~30-40% reduction).
 */
object SelectiveContentReducer {

    /**
     * Trim each chunk by keeping only sentences with the highest keyword overlap with the query.
     * Target: keep at most [maxSentencesPerChunk] sentences per chunk.
     */
    fun reduce(query: String, chunks: List<RetrievedChunk>, maxSentencesPerChunk: Int = 5): List<RetrievedChunk> {
        val queryWords = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (queryWords.isEmpty()) return chunks

        return chunks.map { chunk ->
            val sentences = chunk.content.split(Regex("(?<=[.!?])\\s+")).filter { it.trim().length > 10 }
            if (sentences.size <= maxSentencesPerChunk) return@map chunk

            // Score each sentence by keyword overlap
            val scored = sentences.map { sentence ->
                val sentWords = sentence.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
                val overlap = queryWords.intersect(sentWords).size.toFloat() / queryWords.size.coerceAtLeast(1)
                Pair(sentence, overlap)
            }

            // Keep top-N sentences in original order
            val topIndices = scored
                .mapIndexed { i, (_, score) -> Pair(i, score) }
                .sortedByDescending { it.second }
                .take(maxSentencesPerChunk)
                .map { it.first }
                .toSortedSet()

            val trimmedContent = sentences
                .filterIndexed { i, _ -> i in topIndices }
                .joinToString(" ")

            chunk.copy(content = trimmedContent.ifEmpty { chunk.content.take(400) })
        }
    }
}
