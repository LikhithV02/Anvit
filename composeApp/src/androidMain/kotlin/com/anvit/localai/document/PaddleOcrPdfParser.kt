package com.anvit.localai.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.github.hzkitty.RapidOCR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class PaddleOcrPdfParser(
    private val context: Context,
    private val renderWidthPx: Int = 960,
    private val chunker: PaddleOcrChunker = PaddleOcrChunker()
) : DocumentChunkParser {

    override fun supports(fileName: String): Boolean =
        fileName.endsWith(".pdf", ignoreCase = true)

    override suspend fun parseChunks(fileName: String, bytes: ByteArray): ParsedDocumentChunks =
        withContext(Dispatchers.IO) {
            val ocr = RapidOCR.create(context)
            val pdfFile = writeToCache(fileName, bytes)
            val pages = mutableListOf<PaddleOcrPage>()
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    for (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val bitmap = renderPage(page)
                            try {
                                val result = ocr.run(bitmap)
                                val lines = OcrReflection.extractLines(result)
                                pages.add(PaddleOcrPage(pageIndex + 1, bitmap.width, bitmap.height, lines))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
            runCatching { pdfFile.delete() }
            chunker.chunk(PaddleOcrDocument(fileName, pages))
        }

    private fun writeToCache(fileName: String, bytes: ByteArray): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(context.cacheDir, "paddle_ocr_$safeName")
        FileOutputStream(out).use { it.write(bytes) }
        return out
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val scale = renderWidthPx.toFloat() / page.width.toFloat()
        val width = renderWidthPx
        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private object OcrReflection {
        fun extractLines(result: Any?): List<PaddleOcrLine> {
            if (result == null) return emptyList()
            return findLineItems(result).mapNotNull { item ->
                val text = firstString(item, "getText", "getTxt", "getLabel", "getStrRes", "text", "txt")
                    ?: item.toString().takeIf { it.isNotBlank() }
                val box = firstBox(item, "getBox", "getDtBoxes", "getPoints", "getPosition", "box", "dtBoxes", "points")
                if (text.isNullOrBlank() || box.isNullOrEmpty()) {
                    null
                } else {
                    PaddleOcrLine(
                        text = text,
                        confidence = firstNumber(item, "getConfidence", "getScore", "confidence", "score"),
                        box = box
                    )
                }
            }
        }

        private fun findLineItems(result: Any): List<Any> {
            val candidates = listOf(
                "getTextBlocks",
                "getTextBlockList",
                "getLines",
                "getOcrResults",
                "getRecRes",
                "getResults",
                "getResult",
                "textBlocks",
                "lines",
                "ocrResults",
                "recRes",
                "results",
                "result"
            )
            candidates.forEach { name ->
                val value = readMember(result, name)
                if (value is Iterable<*>) return value.filterNotNull()
                if (value is Array<*>) return value.filterNotNull()
            }
            return emptyList()
        }

        private fun firstString(target: Any, vararg names: String): String? =
            names.firstNotNullOfOrNull { name ->
                readMember(target, name)?.toString()?.takeIf { it.isNotBlank() }
            }

        private fun firstNumber(target: Any, vararg names: String): Double? =
            names.firstNotNullOfOrNull { name ->
                when (val value = readMember(target, name)) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull()
                    else -> null
                }
            }

        private fun firstBox(target: Any, vararg names: String): List<OcrPoint>? {
            names.forEach { name ->
                pointsFrom(readMember(target, name))?.let { return it }
            }
            return null
        }

        private fun pointsFrom(value: Any?): List<OcrPoint>? =
            when (value) {
                is Iterable<*> -> value.mapNotNull { pointFrom(it) }.takeIf { it.isNotEmpty() }
                is Array<*> -> value.mapNotNull { pointFrom(it) }.takeIf { it.isNotEmpty() }
                else -> null
            }

        private fun pointFrom(value: Any?): OcrPoint? {
            if (value == null) return null
            if (value is Iterable<*>) {
                val values = value.filterIsInstance<Number>()
                if (values.size >= 2) return OcrPoint(values[0].toDouble(), values[1].toDouble())
            }
            if (value is Array<*>) {
                val values = value.filterIsInstance<Number>()
                if (values.size >= 2) return OcrPoint(values[0].toDouble(), values[1].toDouble())
            }
            val x = firstNumber(value, "getX", "x")
            val y = firstNumber(value, "getY", "y")
            return if (x != null && y != null) OcrPoint(x, y) else null
        }

        private fun readMember(target: Any, name: String): Any? =
            runCatching {
                if (name.startsWith("get")) {
                    target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(target)
                } else {
                    target.javaClass.fields.firstOrNull { it.name == name }?.get(target)
                        ?: target.javaClass.declaredFields.firstOrNull { it.name == name }?.let { field ->
                            field.isAccessible = true
                            field.get(target)
                        }
                }
            }.getOrNull()
    }
}
