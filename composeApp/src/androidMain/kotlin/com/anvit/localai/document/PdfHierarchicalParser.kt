package com.anvit.localai.document

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.math.abs

class PdfHierarchicalParser(private val context: Context) : DocumentParser {

    override fun supports(fileName: String) = fileName.endsWith(".pdf", ignoreCase = true)

    override suspend fun parse(fileName: String, bytes: ByteArray): SectionNode =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context)
            val document = PDDocument.load(ByteArrayInputStream(bytes))
            try {
                val stripper = HierarchicalStripper()
                stripper.getText(document)
                stripper.rootNode
            } finally {
                document.close()
            }
        }

    private class HierarchicalStripper : PDFTextStripper() {

        val rootNode = SectionNode(title = "Document", level = 0)
        private val nodeStack = mutableListOf(rootNode)
        private val currentListItems = mutableListOf<String>()

        private var baseFontSize = 12f
        private var fontSizesSampled = false
        private val fontSizeSamples = mutableListOf<Float>()

        private val listPrefixRegex = Regex("""^[•‣◦⁃∙*\-–]\s+.+|^\d+[.)]\s+.+""")

        // Footer/false-positive row detection: generic across any Indian listed-company PDF.
        // Covers: registered office block, contact info rows, auditor credentials,
        // generic "For [Firm] LLP" auditor signatures, exchange notification headers, CIN.
        private val footerPhraseRe = Regex(
            """(Registered\s+(Office|Offic)|Corporate\s+Communications|Telefax|""" +
            """Membership\s+No|UDIN:|ICAI\s+Reg|Chartered\s+Accountants|""" +
            """BSE\s+Limited|National\s+Stock\s+Exchange|""" +
            """For\s+[A-Z].+\s+LLP\b|""" +
            """CIN\s+[A-Z]\d)""",
            RegexOption.IGNORE_CASE
        )
        private fun isFooterRow(row: List<PageRun>): Boolean =
            row.any { run -> footerPhraseRe.containsMatchIn(run.text) }

        // Financial token detection: comma-formatted numbers, decimals, percentages, currency
        private val commaNumRe = Regex("""^\d{1,3}(,\d{3})+(\.\d+)?$""")
        private val decimalFinRe = Regex("""^\(?\d+\.\d{1,4}%?\)?$""")
        private val currencyRe = Regex("""^[$₹€£¥]""")
        private val finNumRe = Regex("""^[\d,.()%\-]+$""")
        private fun rowHasLargeFinancialToken(row: List<PageRun>): Boolean {
            for (run in row) {
                var t = run.text.trimStart('$', '₹', '€', '£', '¥', '+').trimStart('(').trimEnd(')')
                if (commaNumRe.matches(t)) return true
                if (decimalFinRe.matches(t)) return true
                if (run.text.contains('%') && finNumRe.matches(t)) return true
                if (currencyRe.containsMatchIn(run.text) && finNumRe.matches(t)) return true
            }
            return false
        }

        private data class PageRun(
            val x: Float,
            val y: Float,
            val endX: Float,
            val text: String,
            val fontSize: Float,
            val isBold: Boolean
        )

        private data class ColumnSegment(val text: String, val x: Float, val endX: Float)

        private val pageRuns = mutableListOf<PageRun>()

        init {
            sortByPosition = true
        }

        private fun flushList() {
            if (currentListItems.isEmpty()) return
            val text = currentListItems.joinToString("\n")
            nodeStack.last().blocks.add(
                Block.ListBlock(currentListItems.toList(), TokenCounter.estimate(text))
            )
            currentListItems.clear()
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            if (textPositions.isEmpty() || text.isBlank()) return

            val firstPos = textPositions[0]
            val fontSize = firstPos.fontSizeInPt

            if (!fontSizesSampled) {
                fontSizeSamples.add(fontSize)
                if (fontSizeSamples.size >= 50) {
                    val sorted = fontSizeSamples.sorted()
                    baseFontSize = sorted[sorted.size / 2]
                    fontSizesSampled = true
                }
            }

            val isBold = firstPos.font?.name?.contains("Bold", ignoreCase = true) == true
            val y = firstPos.yDirAdj

            // Split into column segments if intra-run column gaps are detected
            val columns = extractColumns(textPositions, gapThreshold = 8f)
            if (columns != null) {
                for (col in columns) {
                    pageRuns.add(PageRun(x = col.x, y = y, endX = col.endX,
                        text = col.text, fontSize = fontSize, isBold = isBold))
                }
            } else {
                val lastPos = textPositions.last()
                pageRuns.add(PageRun(x = firstPos.xDirAdj, y = y,
                    endX = lastPos.xDirAdj + lastPos.width,
                    text = text, fontSize = fontSize, isBold = isBold))
            }
        }

        // Detect column gaps within a single text run by analysing word-run X positions.
        // Returns null if no significant column structure is found.
        private fun extractColumns(textPositions: List<TextPosition>, gapThreshold: Float): List<ColumnSegment>? {
            if (textPositions.size < 6) return null

            // Group consecutive non-space characters into word runs
            data class WordRun(val text: String, val startX: Float, val endX: Float)
            val wordRuns = mutableListOf<WordRun>()
            val buf = StringBuilder()
            var wordStartX = 0f
            var wordEndX = 0f

            for (tp in textPositions) {
                val ch = tp.unicode?.trim() ?: ""
                if (ch.isEmpty()) {
                    if (buf.isNotEmpty()) {
                        wordRuns.add(WordRun(buf.toString(), wordStartX, wordEndX))
                        buf.clear()
                    }
                } else {
                    if (buf.isEmpty()) wordStartX = tp.xDirAdj
                    buf.append(ch)
                    wordEndX = tp.xDirAdj + tp.width
                }
            }
            if (buf.isNotEmpty()) wordRuns.add(WordRun(buf.toString(), wordStartX, wordEndX))
            if (wordRuns.size < 3) return null

            // Find large gaps between word runs → column boundaries
            val segments = mutableListOf<ColumnSegment>()
            var segStart = 0
            for (i in 0 until wordRuns.size - 1) {
                val gap = wordRuns[i + 1].startX - wordRuns[i].endX
                if (gap > gapThreshold) {
                    val segText = (segStart..i).joinToString(" ") { wordRuns[it].text }
                    if (segText.isNotBlank())
                        segments.add(ColumnSegment(segText, wordRuns[segStart].startX, wordRuns[i].endX))
                    segStart = i + 1
                }
            }
            val lastSegText = (segStart until wordRuns.size).joinToString(" ") { wordRuns[it].text }
            if (lastSegText.isNotBlank())
                segments.add(ColumnSegment(lastSegText, wordRuns[segStart].startX, wordRuns.last().endX))

            return if (segments.size >= 2) segments else null
        }

        override fun endPage(page: PDPage) {
            processPageRuns()
            pageRuns.clear()
            super.endPage(page)
        }

        private fun processPageRuns() {
            if (pageRuns.isEmpty()) return

            val rows = groupByY(pageRuns, yTolerance = 3f)

            fun isTableRow(row: List<PageRun>): Boolean {
                if (row.size < 2) return false
                return row.zipWithNext().any { (a, b) -> b.x - a.endX > 15f }
            }

            data class TableRegion(val startRow: Int, val endRow: Int)
            val tableRegions = mutableListOf<TableRegion>()
            val tableRowSet = mutableSetOf<Int>()
            var i = 0
            while (i < rows.size) {
                if (isTableRow(rows[i])) {
                    var j = i + 1
                    while (j < rows.size && isTableRow(rows[j])) j++
                    if (j - i >= 2) {
                        tableRegions.add(TableRegion(i, j))
                        for (k in i until j) tableRowSet.add(k)
                    }
                    i = j
                } else {
                    i++
                }
            }
            val tableRegionsByStart = tableRegions.associateBy { it.startRow }

            var rowIdx = 0
            while (rowIdx < rows.size) {
                val tableRegion = tableRegionsByStart[rowIdx]
                if (tableRegion != null) {
                    flushList()
                    val table = buildTableBlock(rows.subList(tableRegion.startRow, tableRegion.endRow))
                    if (table != null) nodeStack.last().blocks.add(table)
                    rowIdx = tableRegion.endRow
                } else if (rowIdx !in tableRowSet) {
                    val row = rows[rowIdx]
                    val merged = row.joinToString(" ") { it.text }.trim()
                    if (merged.isNotBlank()) processTextRun(merged, row.first().fontSize, row.first().isBold)
                    rowIdx++
                } else {
                    rowIdx++
                }
            }
        }

        private fun groupByY(runs: List<PageRun>, yTolerance: Float): List<List<PageRun>> {
            if (runs.isEmpty()) return emptyList()
            val rows = mutableListOf<MutableList<PageRun>>()
            var rowY = runs[0].y
            var currentRow = mutableListOf(runs[0])
            for (run in runs.drop(1)) {
                if (abs(run.y - rowY) <= yTolerance) {
                    currentRow.add(run)
                } else {
                    rows.add(currentRow.sortedBy { it.x }.toMutableList())
                    currentRow = mutableListOf(run)
                    rowY = run.y
                }
            }
            rows.add(currentRow.sortedBy { it.x }.toMutableList())
            return rows
        }

        private fun buildTableBlock(tableRows: List<List<PageRun>>): Block.Table? {
            if (tableRows.isEmpty()) return null
            var cleanRows = tableRows.filterNot { isFooterRow(it) }
            // Trim trailing rows without financial tokens (footer continuation rows bridged in)
            while (cleanRows.isNotEmpty() && !rowHasLargeFinancialToken(cleanRows.last()))
                cleanRows = cleanRows.dropLast(1)
            if (cleanRows.size < 2) return null
            if (!cleanRows.any { rowHasLargeFinancialToken(it) }) return null
            val allXs = cleanRows.flatMap { row -> row.map { it.x } }.sorted()
            val columns = mutableListOf<Float>()
            for (x in allXs) {
                if (columns.isEmpty() || x - columns.last() > 20f) columns.add(x)
            }
            if (columns.size < 2) return null

            fun assignCol(run: PageRun): Int =
                columns.indices.minByOrNull { abs(columns[it] - run.x) } ?: 0

            val cellMatrix = cleanRows.map { row ->
                val cells = Array(columns.size) { StringBuilder() }
                for (run in row) {
                    val col = assignCol(run)
                    if (cells[col].isNotEmpty()) cells[col].append(" ")
                    cells[col].append(run.text.trim())
                }
                cells.map { it.toString().trim() }
            }

            if (cellMatrix.isEmpty()) return null

            // If every row's first cell is a bullet marker, this is a list rendered with spacing —
            // not a real table. Return null so the rows fall through to processTextRun as list items.
            val bulletRegex = Regex("^[•‣◦⁃∙oO*\\-–]$")
            if (cellMatrix.all { row -> row.firstOrNull()?.trim()?.let { bulletRegex.matches(it) } == true }) return null

            val (headers, dataRows) = extractHeadersAndData(cellMatrix)
            val markdown = buildMarkdownFromRows(listOf(headers) + dataRows)
            return Block.Table(markdown, headers, dataRows, TokenCounter.estimate(markdown))
        }

        // Detects multi-row table headers (common in financial PDFs), merges them,
        // and left/right-shifts period labels from empty "ghost" columns onto data columns.
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
                // Try left: nearest non-ghost column with a blank label
                var assigned = false
                for (j in (i - 1) downTo 0) {
                    if (j !in ghostCols) {
                        if (finalHeaders[j].isBlank()) { finalHeaders[j] = label; assigned = true }
                        break
                    }
                }
                // Try right: prepend to nearest non-ghost column
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

        private fun processTextRun(text: String, fontSize: Float, isBold: Boolean) {
            if (listPrefixRegex.matches(text.trim())) {
                currentListItems.add(text.trim())
                return
            } else {
                flushList()
            }

            val headingLevel = when {
                fontSize > baseFontSize + 6f -> 1
                fontSize > baseFontSize + 3f -> 2
                (isBold || fontSize > baseFontSize + 1f) && text.trim().length < 120 -> 3
                else -> 0
            }

            if (headingLevel > 0) {
                val newNode = SectionNode(title = text.trim(), level = headingLevel)
                while (nodeStack.size > 1 && nodeStack.last().level >= headingLevel) {
                    nodeStack.removeAt(nodeStack.lastIndex)
                }
                nodeStack.last().children.add(newNode)
                nodeStack.add(newNode)
            } else {
                nodeStack.last().blocks.add(Block.Text(text, TokenCounter.estimate(text)))
            }
        }

        override fun endDocument(document: PDDocument) {
            flushList()
            fontSizesSampled = true
            super.endDocument(document)
        }
    }
}
