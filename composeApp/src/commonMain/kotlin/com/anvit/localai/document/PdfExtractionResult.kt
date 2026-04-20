package com.anvit.localai.document

data class PdfExtractionResult(
    val text: String,
    val pageCount: Int,
    val fileName: String
)

/** Platform-specific PDF text extractor (androidMain: iText7, iosMain: PDFKit). */
interface PdfExtractor {
    /**
     * Extract text from a platform-native document handle.
     * [documentBytes]: raw PDF bytes read from wherever the platform's file picker gave us.
     */
    suspend fun extract(fileName: String, documentBytes: ByteArray): PdfExtractionResult?
}
