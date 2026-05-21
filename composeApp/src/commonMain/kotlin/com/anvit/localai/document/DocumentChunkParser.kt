package com.anvit.localai.document

data class ParsedDocumentChunks(
    val chunks: List<DocumentChunk>,
    val pageCount: Int
)

interface DocumentChunkParser {
    fun supports(fileName: String): Boolean
    suspend fun parseChunks(fileName: String, bytes: ByteArray): ParsedDocumentChunks
}
