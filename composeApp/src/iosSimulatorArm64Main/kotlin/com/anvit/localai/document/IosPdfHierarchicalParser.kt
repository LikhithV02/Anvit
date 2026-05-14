package com.anvit.localai.document

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSAttributedString
import platform.Foundation.NSMakeRange
import platform.PDFKit.PDFDocument
import platform.UIKit.NSFontAttributeName
import platform.UIKit.UIFont

class IosPdfHierarchicalParser : DocumentParser {

    override fun supports(fileName: String) = fileName.endsWith(".pdf", ignoreCase = true)

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun parse(fileName: String, bytes: ByteArray): SectionNode =
        withContext(Dispatchers.Default) {
            val doc = PDFDocument(data = bytes.toNSData())
                ?: return@withContext SectionNode(title = fileName, level = 0)

            val baseFontSize = computeBaseFontSize(doc)
            val root = SectionNode(title = "Document", level = 0)
            val nodeStack = mutableListOf(root)
            val currentListItems = mutableListOf<String>()
            val listPrefixRegex = Regex("""^[•‣◦⁃∙*\-–]\s+.+|^\d+[.)]\s+.+""")

            fun flushList() {
                if (currentListItems.isEmpty()) return
                val text = currentListItems.joinToString("\n")
                nodeStack.last().blocks.add(
                    Block.ListBlock(currentListItems.toList(), TokenCounter.estimate(text))
                )
                currentListItems.clear()
            }

            val pageCount = doc.pageCount().toInt()
            for (i in 0 until pageCount) {
                val page = doc.pageAtIndex(i.toULong()) ?: continue
                val attrStr = page.attributedString as? NSAttributedString ?: continue
                val fullText = attrStr.string
                val lines = fullText.split("\n")

                // Detect table regions by whitespace-column heuristic
                val tableRanges = detectTableRanges(lines)
                val tableLineIndices = tableRanges.flatMap { it.toList() }.toSet()
                val tablesByStart = tableRanges.associateBy { it.first }

                var lineOffset = 0
                var lineIdx = 0
                while (lineIdx < lines.size) {
                    val line = lines[lineIdx]
                    val lineLen = line.length

                    // Emit entire table region before moving on
                    val tableRange = tablesByStart[lineIdx]
                    if (tableRange != null) {
                        val tableLines = lines.slice(tableRange)
                        val table = buildTableBlock(tableLines)
                        if (table != null) {
                            flushList()
                            nodeStack.last().blocks.add(table)
                        }
                        lineOffset += tableLines.sumOf { it.length + 1 }
                        lineIdx = tableRange.last + 1
                        continue
                    }

                    if (lineIdx in tableLineIndices) {
                        lineOffset += lineLen + 1
                        lineIdx++
                        continue
                    }

                    val trimmed = line.trim()
                    if (trimmed.isBlank()) {
                        lineOffset += lineLen + 1
                        lineIdx++
                        continue
                    }

                    val firstNonSpace = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    val sampleIdx = (lineOffset + firstNonSpace).toULong()
                    val attrs = attrStr.attributesAtIndex(sampleIdx, null)
                    val font = attrs[NSFontAttributeName] as? UIFont
                    val fontSize = font?.pointSize?.toFloat() ?: baseFontSize
                    val isBold = font?.fontName?.contains("Bold", ignoreCase = true) ?: false

                    if (listPrefixRegex.matches(trimmed)) {
                        currentListItems.add(trimmed)
                        lineOffset += lineLen + 1
                        lineIdx++
                        continue
                    } else {
                        flushList()
                    }

                    val headingLevel = when {
                        fontSize > baseFontSize + 6f -> 1
                        fontSize > baseFontSize + 3f -> 2
                        (isBold || fontSize > baseFontSize + 1f) && trimmed.length < 120 -> 3
                        else -> 0
                    }

                    if (headingLevel > 0) {
                        val newNode = SectionNode(title = trimmed, level = headingLevel)
                        while (nodeStack.size > 1 && nodeStack.last().level >= headingLevel) {
                            nodeStack.removeAt(nodeStack.lastIndex)
                        }
                        nodeStack.last().children.add(newNode)
                        nodeStack.add(newNode)
                    } else {
                        nodeStack.last().blocks.add(
                            Block.Text(trimmed, TokenCounter.estimate(trimmed))
                        )
                    }

                    lineOffset += lineLen + 1
                    lineIdx++
                }
            }

            flushList()
            root
        }

    private fun isTableLine(line: String): Boolean {
        if (line.isBlank()) return false
        return line.trim().split(Regex("\\s{3,}")).count { it.isNotBlank() } >= 2
    }

    private fun parseTableColumns(line: String): List<String> =
        line.trim().split(Regex("\\s{3,}")).map { it.trim() }.filter { it.isNotBlank() }

    private fun detectTableRanges(lines: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = -1
        for ((i, line) in lines.withIndex()) {
            if (isTableLine(line)) {
                if (start == -1) start = i
            } else {
                if (start != -1 && i - start >= 2) ranges.add(start until i)
                start = -1
            }
        }
        if (start != -1 && lines.size - start >= 2) ranges.add(start until lines.size)
        return ranges
    }

    private fun buildTableBlock(tableLines: List<String>): Block.Table? {
        val allRows = tableLines.map { parseTableColumns(it) }.filter { it.isNotEmpty() }
        if (allRows.isEmpty()) return null
        val headers = allRows.first()
        val dataRows = if (allRows.size > 1) allRows.drop(1) else emptyList()
        val md = buildMarkdownFromRows(allRows)
        return Block.Table(md, headers, dataRows, TokenCounter.estimate(md))
    }

    private fun buildMarkdownFromRows(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val sb = StringBuilder()
        for ((i, row) in rows.withIndex()) {
            sb.append("| ${row.joinToString(" | ")} |\n")
            if (i == 0) sb.append("| ${row.map { "---" }.joinToString(" | ")} |\n")
        }
        return sb.toString()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun computeBaseFontSize(doc: PDFDocument): Float {
        val samples = mutableListOf<Float>()
        val pageCount = doc.pageCount().toInt()
        outer@ for (i in 0 until pageCount) {
            val page = doc.pageAtIndex(i.toULong()) ?: continue
            val attrStr = page.attributedString as? NSAttributedString ?: continue
            val text = attrStr.string
            val step = maxOf(1, text.length / 50)
            var pos = 0
            while (pos < text.length && samples.size < 50) {
                val attrs = attrStr.attributesAtIndex(pos.toULong(), null)
                val font = attrs[NSFontAttributeName] as? UIFont
                if (font != null) samples.add(font.pointSize.toFloat())
                pos += step
            }
            if (samples.size >= 50) break@outer
        }
        if (samples.isEmpty()) return 12f
        val sorted = samples.sorted()
        return sorted[sorted.size / 2]
    }
}
