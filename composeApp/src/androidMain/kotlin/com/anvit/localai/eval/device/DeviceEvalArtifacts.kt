package com.anvit.localai.eval.device

import com.anvit.localai.agentic.PipelineTrace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeviceEvalRunArtifacts(
    val runId: String,
    val generatedAt: String,
    val manifest: DeviceEvalManifest,
    val traces: List<DeviceEvalTraceRecord>,
    val answers: List<DeviceEvalAnswer>
)

@Serializable
data class DeviceEvalManifest(
    val runId: String,
    val datasetVersion: String,
    val sampleCount: Int,
    val corpusFiles: List<String>,
    val gemmaModelId: String,
    val gemmaModelFile: String,
    val embeddingModelName: String,
    val accelerator: String,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val maxChunks: Int,
    val enableAgenticRag: Boolean,
    val enableSelfCritique: Boolean,
    val useAgentTools: Boolean,
    val documents: List<DeviceEvalDocumentRecord>,
    val totalChunks: Int,
    val ingestionLatencyMs: Long,
    val traceLatencyMs: Long
)

@Serializable
data class DeviceEvalDocumentRecord(
    val docId: String,
    val fileName: String,
    val chunkCount: Int,
    val status: String,
    val sizeBytes: Long,
    val contentHashes: List<String>
)

@Serializable
data class DeviceEvalTraceRecord(
    val sampleId: String,
    val trace: PipelineTrace
)

@Serializable
data class DeviceEvalAnswer(
    val sampleId: String,
    val answer: String
)

object DeviceEvalJson {
    val format = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

fun stableEvalContentHash(content: String): String {
    var hash = -3750763034362895579L
    val prime = 1099511628211L
    content.trim().lowercase().encodeToByteArray().forEach { byte ->
        hash = hash xor (byte.toLong() and 0xffL)
        hash *= prime
    }
    return hash.toString(16)
}
