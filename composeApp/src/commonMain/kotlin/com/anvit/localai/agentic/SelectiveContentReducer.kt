package com.anvit.localai.agentic

import com.anvit.localai.document.TokenCounter
import com.anvit.localai.retrieval.RetrievedChunk

/**
 * Trims retrieved chunks to the most relevant sentences before LLM generation.
 * Reduces token usage and latency (based on MobileRAG paper: ~30-40% reduction).
 */
object SelectiveContentReducer {

    /**
     * Trim each chunk by keeping only sentences with the highest keyword overlap with the query.
     * Target: keep at most [maxSentencesPerChunk] sentences per chunk.
     */
    fun reduce(
        query: String,
        chunks: List<RetrievedChunk>,
        maxSentencesPerChunk: Int = 5,
        maxStructuredTokensPerChunk: Int = 700
    ): List<RetrievedChunk> {
        val queryWords = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (queryWords.isEmpty()) return chunks

        return chunks.map { chunk ->
            if (isTableChunk(chunk.content)) {
                return@map chunk.copy(content = reduceStructuredLines(chunk.content, queryWords, maxStructuredTokensPerChunk))
            }
            if (isListChunk(chunk.content)) {
                return@map chunk.copy(content = reduceStructuredLines(chunk.content, queryWords, maxStructuredTokensPerChunk))
            }

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
                .toHashSet()

            val trimmedContent = sentences
                .filterIndexed { i, _ -> i in topIndices }
                .joinToString(" ")

            chunk.copy(content = trimmedContent.ifEmpty { chunk.content.take(400) })
        }
    }

    private fun reduceStructuredLines(
        content: String,
        queryWords: Set<String>,
        maxTokens: Int
    ): String {
        if (TokenCounter.estimate(content) <= maxTokens) return content

        val lines = content.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return content.take(1200)

        val metadata = lines.filter { isStructuredHeaderLine(it) }.distinct()
        val dataLines = lines.filterNot { isStructuredHeaderLine(it) }
        val rankedData = dataLines
            .mapIndexed { index, line -> IndexedLine(index, line, overlapScore(line, queryWords)) }
            .sortedWith(compareByDescending<IndexedLine> { it.score }.thenBy { it.index })

        val selected = mutableListOf<String>()
        metadata.forEach { selected.add(it) }

        fun currentText(): String = selected.joinToString("\n")
        fun canAdd(line: String): Boolean =
            TokenCounter.estimate((currentText() + "\n" + line).trim()) <= maxTokens

        for (entry in rankedData) {
            if (entry.score <= 0f && selected.size > metadata.size) continue
            if (canAdd(entry.line)) selected.add(entry.line)
        }
        if (selected.size == metadata.size) {
            for (line in dataLines) {
                if (canAdd(line)) selected.add(line) else break
            }
        }

        return selected.joinToString("\n").ifBlank { content.take(1200) }
    }

    private fun overlapScore(line: String, queryWords: Set<String>): Float {
        val words = line.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        return queryWords.intersect(words).size.toFloat() / queryWords.size.coerceAtLeast(1)
    }

    private fun isTableChunk(content: String): Boolean =
        content.lines().any { line ->
            line.startsWith("Table:") ||
            line.startsWith("Table summary:") ||
            line.startsWith("Table (continued)")
        }

    private fun isListChunk(content: String): Boolean =
        content.lines().any { line -> line.startsWith("List summary:") }

    private fun isStructuredHeaderLine(line: String): Boolean =
        line.startsWith("Table:") ||
            line.startsWith("Table summary:") ||
            line.startsWith("Table (continued)") ||
            line.startsWith("List summary:")

    private data class IndexedLine(val index: Int, val line: String, val score: Float)
}
