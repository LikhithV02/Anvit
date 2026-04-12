package com.sage.localai.document

import android.content.Context
import android.net.Uri
import android.util.Log
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

data class PdfExtractionResult(
    val text: String,
    val pageCount: Int,
    val fileName: String
)

object PdfProcessor {
    private const val TAG = "PdfProcessor"

    suspend fun extractFromUri(context: Context, uri: Uri, fileName: String): PdfExtractionResult? =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    extractFromStream(stream, fileName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract PDF from URI: ${e.message}", e)
                null
            }
        }

    suspend fun extractFromFile(file: File): PdfExtractionResult? = withContext(Dispatchers.IO) {
        try {
            PdfReader(file).use { reader ->
                PdfDocument(reader).use { doc ->
                    extractText(doc, file.name)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract PDF from file: ${e.message}", e)
            null
        }
    }

    private fun extractFromStream(stream: InputStream, fileName: String): PdfExtractionResult? {
        return try {
            PdfReader(stream).use { reader ->
                PdfDocument(reader).use { doc ->
                    extractText(doc, fileName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF extraction failed: ${e.message}", e)
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
