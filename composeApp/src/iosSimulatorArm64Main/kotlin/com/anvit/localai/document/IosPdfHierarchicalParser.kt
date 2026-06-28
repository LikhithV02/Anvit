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

    // Matches tokens that look like financial numbers: digits/commas/dots/parens/percent/dash
    private val financialNumRe = Regex("""^[\d,.()\-%]+$""")

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
            val pendingTextLines = mutableListOf<String>()
            val listPrefixRegex = Regex("""^[•‣◦⁃∙*\-–]\s+.+|^\d+[.)]\s+.+""")

            fun flushList() {
                if (currentListItems.isEmpty()) return
                val text = currentListItems.joinToString("\n")
                nodeStack.last().blocks.add(
                    Block.ListBlock(currentListItems.toList(), TokenCounter.estimate(text))
                )
                currentListItems.clear()
            }

            // Merge accumulated wrapped-paragraph lines into one TEXT block.
            fun flushTextBuffer() {
                if (pendingTextLines.isEmpty()) return
                val merged = pendingTextLines.joinToString(" ").trim()
                if (merged.isNotBlank())
                    nodeStack.last().blocks.add(Block.Text(merged, TokenCounter.estimate(merged)))
                pendingTextLines.clear()
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
                            flushTextBuffer()
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
                        // Blank line = paragraph boundary → flush pending text
                        flushTextBuffer()
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
                        flushTextBuffer()
                        currentListItems.add(trimmed)
                        lineOffset += lineLen + 1
                        lineIdx++
                        continue
                    } else {
                        flushList()
                    }

                    // Guard: lines heavy with financial numbers or period labels are data/column-header
                    // rows, not section headings.
                    val lineTokens = trimmed.split(Regex("\\s+"))
                    val numericTokenCount = lineTokens.count { financialNumRe.matches(it) }
                    val isNumericHeavy = numericTokenCount >= 2
                    // Period labels like "FY26 FY25", "4Q FY26 3Q FY26" — all tokens contain a digit
                    val isColumnHeaderRow = lineTokens.size in 2..6 &&
                        lineTokens.all { tok -> tok.any { it.isDigit() } }

                    val headingLevel = when {
                        fontSize > baseFontSize + 6f -> 1
                        fontSize > baseFontSize + 3f -> 2
                        (isBold || fontSize > baseFontSize + 1f) && trimmed.length < 120 &&
                            !isNumericHeavy && !isColumnHeaderRow -> 3
                        else -> 0
                    }

                    if (headingLevel > 0) {
                        flushTextBuffer()
                        val newNode = SectionNode(title = trimmed, level = headingLevel)
                        while (nodeStack.size > 1 && nodeStack.last().level >= headingLevel) {
                            nodeStack.removeAt(nodeStack.lastIndex)
                        }
                        nodeStack.last().children.add(newNode)
                        nodeStack.add(newNode)
                    } else {
                        // Accumulate into text buffer; flush when buffer exceeds ~800 chars
                        pendingTextLines.add(trimmed)
                        if (pendingTextLines.sumOf { it.length } > 800) flushTextBuffer()
                    }

                    lineOffset += lineLen + 1
                    lineIdx++
                }
                // End of page — flush any remaining buffered text
                flushTextBuffer()
            }

            flushTextBuffer()
            flushList()
            root
        }

    // A line looks like a table row if it has ≥2 non-blank tokens separated by 3+ spaces,
    // OR if it is a numeric-grid line (financial data row with single-space column separation).
    private fun isTableLine(line: String): Boolean {
        if (line.isBlank()) return false
        val trimmed = line.trim()
        if (trimmed.split(Regex("\\s{3,}")).count { it.isNotBlank() } >= 2) return true
        return isNumericGridLine(trimmed)
    }

    // Returns true when ≥3 tokens look like financial numbers and make up ≥40% of the line's tokens.
    // This catches financial table rows where PDFKit collapses column gaps to a single space.
    private fun isNumericGridLine(line: String): Boolean {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.size < 4) return false
        val numericCount = tokens.count { financialNumRe.matches(it) }
        return numericCount >= 3 && numericCount.toFloat() / tokens.size >= 0.4f
    }

    private fun parseTableColumns(line: String): List<String> {
        val trimmed = line.trim()
        // Primary: 3+ space gap detection (works for wide-spaced columns)
        val spaceSplit = trimmed.split(Regex("\\s{3,}"))
            .map { it.trim() }.filter { it.isNotBlank() }
        if (spaceSplit.size >= 2) return spaceSplit
        // Secondary: numeric-grid splitting — split at the first "big number" token (3+ digits)
        return parseNumericGridColumns(trimmed).filter { it.isNotBlank() }
    }

    // Splits "1 Gross Revenue 325,290 293,829 288,138 12.9 1,175,919 1,071,174"
    // into ["1 Gross Revenue", "325,290", "293,829", "288,138", "12.9", "1,175,919", "1,071,174"].
    // Finds the first token that contains 3+ consecutive digits (a "big number") and treats
    // everything before it as the label, everything from it onward as individual cells.
    private fun parseNumericGridColumns(line: String): List<String> {
        val tokens = line.trim().split(Regex("\\s+"))
        val bigNumRe = Regex("""\d{3,}""")
        val firstBigNumIdx = tokens.indexOfFirst { token ->
            bigNumRe.containsMatchIn(token.replace(",", "").replace("(", "").replace(")", ""))
        }.takeIf { it >= 0 } ?: return tokens
        val label = tokens.take(firstBigNumIdx).joinToString(" ")
        val values = tokens.drop(firstBigNumIdx)
        return if (label.isBlank()) values else listOf(label) + values
    }

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

        // If every row's first cell is a bullet marker, this is a spaced-out list — not a table.
        val bulletRegex = Regex("^[•‣◦⁃∙oO*\\-–]$")
        if (allRows.all { row -> row.firstOrNull()?.trim()?.let { bulletRegex.matches(it) } == true }) return null

        val (headers, dataRows) = extractHeadersAndData(allRows)
        val md = buildMarkdownFromRows(listOf(headers) + dataRows)
        return Block.Table(md, headers, dataRows, TokenCounter.estimate(md))
    }

    // Detects multi-row table headers (common in financial PDFs), merges them,
    // and reassigns period labels from empty "ghost" columns onto adjacent data columns.
    private fun extractHeadersAndData(cellMatrix: List<List<String>>): Pair<List<String>, List<List<String>>> {
        if (cellMatrix.size < 3) return Pair(cellMatrix.first(), cellMatrix.drop(1))

        val row0 = cellMatrix[0]
        val row1 = cellMatrix[1]

        // A sub-header row has >50% empty cells and no 4+ digit numbers
        val emptyCount = row1.count { it.isEmpty() || it == "(empty)" }
        val hasLargeNumber = row1.any { it.replace(",", "").matches(Regex("[0-9]{4,}.*")) }
        if (emptyCount < row1.size / 2 || hasLargeNumber) return Pair(row0, cellMatrix.drop(1))

        val colCount = maxOf(row0.size, row1.size)
        val merged = List(colCount) { i ->
            val h0 = row0.getOrElse(i) { "" }.trim()
            val h1 = row1.getOrElse(i) { "" }.trim()
            when {
                h0.isNotBlank() && h1.isNotBlank() -> "$h0 $h1"
                h0.isNotBlank() -> h0
                h1.isNotBlank() -> h1
                else -> ""
            }
        }

        val dataRows = cellMatrix.drop(2)

        // Ghost columns: have a merged label but no actual data in any data row
        val ghostCols = (0 until colCount).filter { i ->
            merged[i].isNotBlank() &&
            dataRows.all { row -> row.getOrElse(i) { "" }.let { it.isEmpty() || it == "(empty)" } }
        }.toSet()

        val finalHeaders = merged.toMutableList()
        for (i in ghostCols.sorted()) {
            val label = merged[i]
            if (label.isBlank()) continue
            var assigned = false
            for (j in (i - 1) downTo 0) {
                if (j !in ghostCols) {
                    if (finalHeaders[j].isBlank()) { finalHeaders[j] = label; assigned = true }
                    break
                }
            }
            if (!assigned) {
                for (j in (i + 1) until colCount) {
                    if (j !in ghostCols) {
                        finalHeaders[j] = if (finalHeaders[j].isBlank()) label else "$label ${finalHeaders[j]}"
                        break
                    }
                }
            }
        }

        val keepCols = (0 until colCount).filter { it !in ghostCols }
        val filteredHeaders = keepCols.map { finalHeaders[it].ifBlank { "Col ${it + 1}" } }
        val filteredData = dataRows.map { row -> keepCols.map { row.getOrElse(it) { "" } } }
        return Pair(filteredHeaders, filteredData)
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
