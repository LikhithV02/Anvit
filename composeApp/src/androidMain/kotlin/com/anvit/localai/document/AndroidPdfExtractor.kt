package com.anvit.localai.document

import android.util.Log
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * Android implementation of [PdfExtractor] using iText7.
 */
class AndroidPdfExtractor : PdfExtractor {

    companion object {
        private const val TAG = "AndroidPdfExtractor"
    }

    override suspend fun extract(fileName: String, documentBytes: ByteArray): PdfExtractionResult? =
        withContext(Dispatchers.IO) {
            try {
                ByteArrayInputStream(documentBytes).use { stream ->
                    PdfReader(stream).use { reader ->
                        PdfDocument(reader).use { doc ->
                            extractText(doc, fileName)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PDF extraction failed for $fileName: ${e.message}", e)
                null
            }
        }

    private fun extractText(doc: PdfDocument, fileName: String): PdfExtractionResult {
        val sb = StringBuilder()
        val pageCount = doc.numberOfPages
        for (i in 1..pageCount) {
            try {
                val pageText = PdfTextExtractor.getTextFromPage(doc.getPage(i))
                if (pageText.isNotBlank()) {
                    sb.append(pageText)
                    if (!pageText.endsWith("\n")) sb.append("\n")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract text from page $i: ${e.message}")
            }
        }
        val text = sb.toString().trim()
        Log.d(TAG, "Extracted ${text.length} chars from $pageCount pages of $fileName")
        return PdfExtractionResult(text, pageCount, fileName)
    }
}
