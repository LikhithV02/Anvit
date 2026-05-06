package com.anvit.localai.eval.dataset

import com.anvit.localai.eval.services.GeminiClient

class DatasetValidator(
    private val client: GeminiClient
) {
    fun isValid(sample: EvalSample, chunks: List<ChunkRecord>): Boolean {
        if (sample.expectedRefusal) return sample.expectedChunkIds.isEmpty()
        val context = chunks.filter { it.chunkId in sample.expectedChunkIds }
            .joinToString("\n---\n") { "[${it.chunkId}]\n${it.content.take(1200)}" }
        if (context.isBlank()) return false
        val answer = client.generateText(
            prompt = """
Can the question be answered from the provided chunks?
Return only YES or NO.

Question: ${sample.question}

Chunks:
$context
            """.trimIndent(),
            systemPrompt = "You validate synthetic RAG eval samples."
        ).trim().uppercase()
        return answer.contains("YES")
    }
}
