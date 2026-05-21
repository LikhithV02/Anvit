package com.anvit.localai.document

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class PaddleOcrChunkExportTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun exportChunksFromSavedPaddleOcrJson() {
        val root = File(System.getProperty("user.dir") ?: ".").parentFile ?: File(".")
        val inputDir = File(root, "experiments/paddleocr_test/outputs")
        val outputDir = File(root, "experiments/paddleocr_test/chunk_outputs").apply { mkdirs() }
        val inputs = inputDir.listFiles { file -> file.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }

        assertTrue(inputs.isNotEmpty(), "No PaddleOCR JSON files found in ${inputDir.absolutePath}")

        val chunker = PaddleOcrChunker()
        val summaries = inputs.map { input ->
            val fixture = json.decodeFromString(FixtureDocument.serializer(), input.readText())
            val parsed = chunker.chunk(fixture.toDocument())
            val baseName = input.nameWithoutExtension

            File(outputDir, "$baseName.chunks.json").writeText(parsed.chunks.toExportJson(), Charsets.UTF_8)
            File(outputDir, "$baseName.chunks.csv").writeText(parsed.chunks.toExportCsv(), Charsets.UTF_8)
            File(outputDir, "$baseName.chunks.md").writeText(parsed.chunks.toChunkMarkdown(fixture.document), Charsets.UTF_8)

            ExportSummary(
                document = fixture.document,
                source = input.name,
                pageCount = parsed.pageCount,
                chunkCount = parsed.chunks.size,
                sectionCount = parsed.chunks.count { it.chunkType == ChunkTypes.SECTION },
                textCount = parsed.chunks.count { it.chunkType == ChunkTypes.TEXT },
                tableCount = parsed.chunks.count { it.chunkType == ChunkTypes.TABLE },
                tablePartCount = parsed.chunks.count { it.chunkType == ChunkTypes.TABLE_PART },
                averageTokens = parsed.chunks.map { it.tokenCount }.average().takeIf { !it.isNaN() } ?: 0.0
            )
        }

        File(outputDir, "summary.md").writeText(summaries.toMarkdown(outputDir), Charsets.UTF_8)
        println("Exported PaddleOCR chunks to ${outputDir.absolutePath}")
    }

    private fun FixtureDocument.toDocument(): PaddleOcrDocument =
        PaddleOcrDocument(
            fileName = document,
            pages = pages.map { page ->
                PaddleOcrPage(
                    page = page.page,
                    width = page.width,
                    height = page.height,
                    lines = page.lines.map { line ->
                        PaddleOcrLine(
                            text = line.text,
                            confidence = line.confidence,
                            box = line.box.orEmpty().map { OcrPoint(it.x, it.y) }
                        )
                    }
                )
            }
        )

    private fun List<DocumentChunk>.toExportJson(): String =
        joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { chunk ->
            buildString {
                appendLine("  {")
                appendLine("    \"id\": ${chunk.id.jsonString()},")
                appendLine("    \"chunkType\": ${chunk.chunkType.jsonString()},")
                appendLine("    \"parentChunkId\": ${chunk.parentChunkId.jsonStringOrNull()},")
                appendLine("    \"sectionId\": ${chunk.sectionId.jsonStringOrNull()},")
                appendLine("    \"groupId\": ${chunk.groupId.jsonStringOrNull()},")
                appendLine("    \"isGroupHead\": ${chunk.isGroupHead},")
                appendLine("    \"pageStart\": ${chunk.pageStart},")
                appendLine("    \"pageEnd\": ${chunk.pageEnd},")
                appendLine("    \"tokenCount\": ${chunk.tokenCount},")
                appendLine("    \"hierarchyPath\": [${chunk.hierarchyPath.joinToString(",") { it.jsonString() }}],")
                appendLine("    \"bbox\": ${chunk.bboxJson.ifBlank { "[]" }},")
                appendLine("    \"rowRange\": ${chunk.rowRangeJson.ifBlank { "null" }},")
                appendLine("    \"content\": ${chunk.content.jsonString()}")
                append("  }")
            }
        }

    private fun List<DocumentChunk>.toExportCsv(): String {
        val chunks = this
        return buildString {
            appendLine("id,chunkType,parentChunkId,sectionId,groupId,isGroupHead,pageStart,pageEnd,tokenCount,hierarchyPath,bboxJson,rowRangeJson,content")
            chunks.forEach { chunk ->
                appendLine(
                    listOf(
                        chunk.id,
                        chunk.chunkType,
                        chunk.parentChunkId.orEmpty(),
                        chunk.sectionId.orEmpty(),
                        chunk.groupId.orEmpty(),
                        chunk.isGroupHead.toString(),
                        chunk.pageStart.toString(),
                        chunk.pageEnd.toString(),
                        chunk.tokenCount.toString(),
                        chunk.hierarchyPath.joinToString(" > "),
                        chunk.bboxJson,
                        chunk.rowRangeJson,
                        chunk.content
                    ).joinToString(",") { it.csvCell() }
                )
            }
        }
    }

    private fun List<DocumentChunk>.toChunkMarkdown(documentName: String): String {
        val chunks = this
        return buildString {
            appendLine("# $documentName")
            appendLine()
            chunks.forEachIndexed { index, chunk ->
                appendLine("## Chunk ${index + 1}")
                appendLine()
                appendLine("Type: `${chunk.chunkType}`")
                appendLine("Pages: `${chunk.pageStart}-${chunk.pageEnd}`")
                if (chunk.hierarchyPath.isNotEmpty()) {
                    appendLine("Hierarchy: `${chunk.hierarchyPath.joinToString(" > ")}`")
                }
                appendLine()
                appendLine(chunk.content.trim())
                appendLine()
            }
        }
    }

    private fun List<ExportSummary>.toMarkdown(outputDir: File): String {
        val summaries = this
        return buildString {
            appendLine("# PaddleOCR Chunk Export Summary")
            appendLine()
            appendLine("Output directory: `${outputDir.absolutePath}`")
            appendLine()
            appendLine("| Document | Pages | Chunks | Sections | Text | Tables | Table Parts | Avg Tokens |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|---:|")
            summaries.forEach { item ->
                appendLine("| ${item.document} | ${item.pageCount} | ${item.chunkCount} | ${item.sectionCount} | ${item.textCount} | ${item.tableCount} | ${item.tablePartCount} | ${"%.1f".format(item.averageTokens)} |")
            }
        }
    }

    private fun String.csvCell(): String =
        "\"${replace("\"", "\"\"").replace("\n", "\\n")}\""

    private fun String?.jsonStringOrNull(): String =
        this?.jsonString() ?: "null"

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

    private data class ExportSummary(
        val document: String,
        val source: String,
        val pageCount: Int,
        val chunkCount: Int,
        val sectionCount: Int,
        val textCount: Int,
        val tableCount: Int,
        val tablePartCount: Int,
        val averageTokens: Double
    )

    @Serializable
    private data class FixtureDocument(
        val document: String,
        val pages: List<FixturePage>
    )

    @Serializable
    private data class FixturePage(
        val page: Int,
        val width: Int,
        val height: Int,
        val lines: List<FixtureLine>
    )

    @Serializable
    private data class FixtureLine(
        val text: String,
        val confidence: Double? = null,
        val box: List<FixturePoint>? = null
    )

    @Serializable
    private data class FixturePoint(val x: Double, val y: Double)
}
