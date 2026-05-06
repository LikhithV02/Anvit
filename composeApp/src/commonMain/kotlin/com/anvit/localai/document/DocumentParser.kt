package com.anvit.localai.document

interface DocumentParser {
    fun supports(fileName: String): Boolean
    suspend fun parse(fileName: String, bytes: ByteArray): SectionNode
}
