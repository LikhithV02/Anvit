package com.anvit.localai.eval.dataset

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class EvalDataset(
    val version: String = "v1",
    val description: String = "",
    val samples: List<EvalSample> = emptyList()
) {
    companion object {
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun load(file: File): EvalDataset = json.decodeFromString(serializer(), file.readText())
        fun write(file: File, dataset: EvalDataset) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer(), dataset))
        }
    }
}

@Serializable
data class EvalSample(
    val id: String,
    val type: EvalQuestionType,
    val question: String,
    @SerialName("expected_chunk_ids")
    val expectedChunkIds: List<String> = emptyList(),
    @SerialName("expected_content_hashes")
    val expectedContentHashes: List<String> = emptyList(),
    @SerialName("expected_doc_ids")
    val expectedDocIds: List<String> = emptyList(),
    @SerialName("gold_answer")
    val goldAnswer: String = "",
    val rationale: String = "",
    val difficulty: String = "medium",
    @SerialName("expected_route")
    val expectedRoute: String? = null,
    @SerialName("expected_refusal")
    val expectedRefusal: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
enum class EvalQuestionType {
    @SerialName("single_hop")
    SINGLE_HOP,

    @SerialName("multi_hop")
    MULTI_HOP,

    @SerialName("table_lookup")
    TABLE_LOOKUP,

    @SerialName("adversarial")
    ADVERSARIAL
}

data class ChunkRecord(
    val chunkId: String,
    val docId: String,
    val fileName: String,
    val groupId: String?,
    val hierarchyPath: String,
    val content: String
)
