package com.anvit.localai.document

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.PDFKit.PDFDocument

class IosPdfExtractor : PdfExtractor {
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun extract(fileName: String, documentBytes: ByteArray): PdfExtractionResult? =
        withContext(Dispatchers.Default) {
            val doc = PDFDocument(data = documentBytes.toNSData()) ?: return@withContext null
            val text = doc.string ?: return@withContext null
            if (text.isBlank()) return@withContext null
            PdfExtractionResult(
                text      = text,
                pageCount = doc.pageCount().toInt(),
                fileName  = fileName
            )
        }
}
