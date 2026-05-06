package com.anvit.localai.eval.runner

import com.anvit.localai.agentic.AgentStep
import com.anvit.localai.retrieval.RetrievedChunk

class TraceCapture {
    val steps: MutableList<AgentStep> = mutableListOf()
    val sourceSnapshots: MutableList<List<RetrievedChunk>> = mutableListOf()

    suspend fun onStep(step: AgentStep) {
        steps.add(step)
    }

    suspend fun onSources(chunks: List<RetrievedChunk>) {
        sourceSnapshots.add(chunks)
    }
}
