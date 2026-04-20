package com.anvit.localai.document

/**
 * iOS stub implementation of [PdfExtractor] for v1.
 *
 * v2 will use PDFKit (PDFDocument.string) for full text extraction.
 * The v1 stub returns null so the UI can inform the user that PDF
 * ingestion is not yet available on iOS.
 */
class IosPdfExtractor : PdfExtractor {
    override suspend fun extract(fileName: String, documentBytes: ByteArray): PdfExtractionResult? {
        println("IosPdfExtractor: PDF ingestion not yet implemented on iOS (v1 stub)")
        return null
    }
}
