package com.anvit.experiments.paddleocr

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import io.github.hzkitty.RapidOCR
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(20, 35, 45))
            textSize = 16f
            setPadding(40, 40, 40, 40)
            text = "Starting PaddleOCR experiment..."
        }
        setContentView(statusView)

        if (RUNNING.compareAndSet(false, true)) {
            Thread {
                try {
                    runExperiment()
                } finally {
                    RUNNING.set(false)
                }
            }.start()
        } else {
            postStatus("OCR experiment is already running. Waiting for current run to finish...")
        }
    }

    private fun runExperiment() {
        val startedAt = SystemClock.elapsedRealtime()
        val outputDir = externalOutputDir()
        val failures = mutableListOf<String>()
        val documentSummaries = mutableListOf<DocumentSummary>()
        val documents = listOf(
            "38a2f910-438f-4fc0-8abc-c6cd5933b5ac.pdf",
            "24042026_Media_Release_RIL_Q4_FY2025-26_Financial_and_Operational_Performance.pdf",
        )

        try {
            postStatus("Loading PaddleOCR-compatible Android runtime...")
            val ocr = RapidOCR.create(this)

            documents.forEachIndexed { index, assetName ->
                postStatus("OCR ${index + 1}/${documents.size}: $assetName")
                try {
                    documentSummaries += runDocument(assetName, ocr, outputDir)
                } catch (error: Throwable) {
                    val message = "$assetName failed: ${error.javaClass.simpleName}: ${error.message}"
                    failures += message
                    Log.e(TAG, message, error)
                }
            }
        } catch (error: Throwable) {
            val message = "Runtime initialization failed: ${error.javaClass.simpleName}: ${error.message}"
            failures += message
            Log.e(TAG, message, error)
        }

        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        writeSummary(outputDir, documentSummaries, failures, elapsedMs)
        postStatus("Done. Outputs saved to:\n${outputDir.absolutePath}")
        Log.i(TAG, "PADDLE_OCR_EXPERIMENT_DONE ${outputDir.absolutePath}")
    }

    private fun runDocument(assetName: String, ocr: RapidOCR, outputDir: File): DocumentSummary {
        val startedAt = SystemClock.elapsedRealtime()
        val pdfFile = copyAssetToCache(assetName)
        val pageResults = mutableListOf<PageResult>()

        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                for (pageIndex in 0 until renderer.pageCount) {
                    renderer.openPage(pageIndex).use { page ->
                        val bitmap = renderPage(page)
                        val pageStartedAt = SystemClock.elapsedRealtime()
                        val result = ocr.run(bitmap)
                        val elapsedMs = SystemClock.elapsedRealtime() - pageStartedAt
                        val extracted = OcrReflection.extract(result)
                        pageResults += PageResult(
                            page = pageIndex + 1,
                            width = bitmap.width,
                            height = bitmap.height,
                            elapsedMs = elapsedMs,
                            text = extracted.text,
                            rawResult = result.toString(),
                            lines = extracted.lines,
                        )
                        bitmap.recycle()
                        Log.i(TAG, "$assetName page ${pageIndex + 1}/${renderer.pageCount}: ${extracted.lines.size} lines")
                    }
                }
            }
        }

        val baseName = assetName.removeSuffix(".pdf")
        File(outputDir, "$baseName.txt").writeText(pageResults.toText(assetName), Charsets.UTF_8)
        File(outputDir, "$baseName.json").writeText(pageResults.toJson(assetName), Charsets.UTF_8)

        return DocumentSummary(
            name = assetName,
            pages = pageResults.size,
            nonEmptyPages = pageResults.count { it.text.isNotBlank() || it.lines.isNotEmpty() },
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
        )
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val scale = RENDER_WIDTH_PX.toFloat() / page.width.toFloat()
        val width = RENDER_WIDTH_PX
        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun copyAssetToCache(assetName: String): File {
        val outFile = File(cacheDir, assetName)
        assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    private fun externalOutputDir(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "ocr_outputs").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun writeSummary(
        outputDir: File,
        summaries: List<DocumentSummary>,
        failures: List<String>,
        elapsedMs: Long,
    ) {
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val summary = buildString {
            appendLine("# PaddleOCR Android Experiment Summary")
            appendLine()
            appendLine("- Generated at: $generatedAt")
            appendLine("- Runtime: rapidocr4j-android 1.0.0 (RapidOCR / PaddleOCR-derived ONNX pipeline)")
            appendLine("- Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("- Android SDK: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("- Render width: $RENDER_WIDTH_PX px")
            appendLine("- Total elapsed: ${elapsedMs} ms")
            appendLine("- Output directory on device: ${outputDir.absolutePath}")
            appendLine()
            appendLine("## Documents")
            summaries.forEach { item ->
                appendLine("- ${item.name}: ${item.pages} pages, ${item.nonEmptyPages} non-empty pages, ${item.elapsedMs} ms")
            }
            if (failures.isNotEmpty()) {
                appendLine()
                appendLine("## Failures")
                failures.forEach { appendLine("- $it") }
            }
        }
        File(outputDir, "summary.md").writeText(summary, Charsets.UTF_8)
    }

    private fun postStatus(message: String) {
        Log.i(TAG, message)
        runOnUiThread {
            statusView.text = message
        }
    }

    companion object {
        private const val TAG = "PaddleOcrExperiment"
        private const val RENDER_WIDTH_PX = 960
        private val RUNNING = AtomicBoolean(false)
    }
}

private data class DocumentSummary(
    val name: String,
    val pages: Int,
    val nonEmptyPages: Int,
    val elapsedMs: Long,
)

private data class PageResult(
    val page: Int,
    val width: Int,
    val height: Int,
    val elapsedMs: Long,
    val text: String,
    val rawResult: String,
    val lines: List<OcrLine>,
)

private data class OcrLine(
    val text: String,
    val confidence: Double?,
    val box: List<Point>?,
)

private data class Point(
    val x: Double,
    val y: Double,
)

private data class ExtractedOcr(
    val text: String,
    val lines: List<OcrLine>,
)

private object OcrReflection {
    fun extract(result: Any?): ExtractedOcr {
        if (result == null) return ExtractedOcr("", emptyList())
        val lines = findLineItems(result).mapNotNull { item ->
            val text = firstString(item, "getText", "getTxt", "getLabel", "getStrRes", "text", "txt")
                ?: item.toString().takeIf { it.isNotBlank() }
            text?.let {
                OcrLine(
                    text = it,
                    confidence = firstNumber(item, "getConfidence", "getScore", "confidence", "score"),
                    box = firstBox(item, "getBox", "getDtBoxes", "getPoints", "getPosition", "box", "dtBoxes", "points"),
                )
            }
        }
        val text = lines.joinToString(separator = "\n") { it.text }.ifBlank { result.toString() }
        return ExtractedOcr(text, lines)
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
            "result",
        )
        candidates.forEach { name ->
            val value = readMember(result, name)
            if (value is Iterable<*>) return value.filterNotNull()
            if (value is Array<*>) return value.filterNotNull()
        }
        return emptyList()
    }

    private fun firstString(target: Any, vararg names: String): String? {
        return names.firstNotNullOfOrNull { name ->
            readMember(target, name)?.toString()?.takeIf { it.isNotBlank() }
        }
    }

    private fun firstNumber(target: Any, vararg names: String): Double? {
        return names.firstNotNullOfOrNull { name ->
            when (val value = readMember(target, name)) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
        }
    }

    private fun firstBox(target: Any, vararg names: String): List<Point>? {
        names.forEach { name ->
            val value = readMember(target, name)
            pointsFrom(value)?.let { return it }
        }
        return null
    }

    private fun pointsFrom(value: Any?): List<Point>? {
        return when (value) {
            is Iterable<*> -> value.mapNotNull { pointFrom(it) }.takeIf { it.isNotEmpty() }
            is Array<*> -> value.mapNotNull { pointFrom(it) }.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun pointFrom(value: Any?): Point? {
        if (value == null) return null
        if (value is Iterable<*>) {
            val values = value.filterIsInstance<Number>()
            if (values.size >= 2) return Point(values[0].toDouble(), values[1].toDouble())
        }
        if (value is Array<*>) {
            val values = value.filterIsInstance<Number>()
            if (values.size >= 2) return Point(values[0].toDouble(), values[1].toDouble())
        }
        val x = firstNumber(value, "getX", "x")
        val y = firstNumber(value, "getY", "y")
        return if (x != null && y != null) Point(x, y) else null
    }

    private fun readMember(target: Any, name: String): Any? {
        return runCatching {
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

private fun List<PageResult>.toText(assetName: String): String {
    val pages = this
    return buildString {
        appendLine("# $assetName")
        appendLine()
        pages.forEach { page ->
            appendLine("## Page ${page.page} (${page.width}x${page.height}, ${page.elapsedMs} ms)")
            appendLine()
            appendLine(page.text.ifBlank { "[no text recognized]" })
            appendLine()
        }
    }
}

private fun List<PageResult>.toJson(assetName: String): String {
    val pages = this
    return buildString {
        appendLine("{")
        appendLine("  \"document\": ${assetName.jsonString()},")
        appendLine("  \"pages\": [")
        pages.forEachIndexed { index, page ->
            appendLine("    {")
            appendLine("      \"page\": ${page.page},")
            appendLine("      \"width\": ${page.width},")
            appendLine("      \"height\": ${page.height},")
            appendLine("      \"elapsedMs\": ${page.elapsedMs},")
            appendLine("      \"text\": ${page.text.jsonString()},")
            appendLine("      \"rawResult\": ${page.rawResult.jsonString()},")
            appendLine("      \"lines\": [")
            page.lines.forEachIndexed { lineIndex, line ->
                append("        {\"text\": ${line.text.jsonString()}, \"confidence\": ")
                append(line.confidence?.toString() ?: "null")
                append(", \"box\": ")
                append(line.box.toJson())
                append("}")
                if (lineIndex != page.lines.lastIndex) append(",")
                appendLine()
            }
            appendLine("      ]")
            append("    }")
            if (index != pages.lastIndex) append(",")
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }
}

private fun List<Point>?.toJson(): String {
    if (this == null) return "null"
    return joinToString(prefix = "[", postfix = "]") { point ->
        "{\"x\": ${point.x}, \"y\": ${point.y}}"
    }
}

private fun String.jsonString(): String {
    val escaped = buildString {
        this@jsonString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
    return "\"$escaped\""
}
