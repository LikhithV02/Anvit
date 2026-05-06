package com.anvit.localai.eval.metrics

import com.anvit.localai.agentic.PipelineTrace
import com.anvit.localai.agentic.TraceChunk
import com.anvit.localai.eval.dataset.EvalQuestionType
import com.anvit.localai.eval.dataset.EvalSample
import kotlin.test.Test
import kotlin.test.assertEquals

class RetrievalMetricsTest {
    @Test
    fun duplicateRetrievedHashesDoNotInflateRecallOrPrecisionHits() {
        val sample = EvalSample(
            id = "q1",
            type = EvalQuestionType.SINGLE_HOP,
            question = "What is the value?",
            expectedContentHashes = listOf("same-hash")
        )
        val trace = PipelineTrace(
            queryId = "q1",
            userQuery = sample.question,
            route = "SINGLE_SHOT",
            finalChunkIds = listOf("c1", "c2", "c3"),
            finalChunks = listOf(
                traceChunk("c1", "same-hash"),
                traceChunk("c2", "same-hash"),
                traceChunk("c3", "same-hash")
            )
        )

        val score = RetrievalMetrics.score(sample, trace)

        assertEquals(1.0, score.recallAt5)
        assertEquals(1.0 / 3.0, score.precisionAt5)
    }

    private fun traceChunk(id: String, hash: String): TraceChunk = TraceChunk(
        chunkId = id,
        docId = "doc",
        fileName = "doc.pdf",
        chunkIndex = 0,
        score = 1f,
        vectorScore = 0f,
        bm25Rank = 1,
        contentHash = hash,
        contentPreview = "content"
    )
}
