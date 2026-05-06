package com.anvit.localai.document

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * A copy of PdfHierarchicalParser that uses desktop pdfbox instead of pdfbox-android.
 * This is meant to be used only in local JVM unit tests where an Android Context is not available.
 */
class DesktopPdfHierarchicalParser : DocumentParser {

    override fun supports(fileName: String) = fileName.endsWith(".pdf", ignoreCase = true)

    override suspend fun parse(fileName: String, bytes: ByteArray): SectionNode =
        withContext(Dispatchers.IO) {
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

        private fun extractColumns(textPositions: List<TextPosition>, gapThreshold: Float): List<ColumnSegment>? {
            if (textPositions.size < 6) return null

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
            val allXs = tableRows.flatMap { row -> row.map { it.x } }.sorted()
            val columns = mutableListOf<Float>()
            for (x in allXs) {
                if (columns.isEmpty() || x - columns.last() > 20f) columns.add(x)
            }
            if (columns.size < 2) return null

            fun assignCol(run: PageRun): Int =
                columns.indices.minByOrNull { abs(columns[it] - run.x) } ?: 0

            val cellMatrix = tableRows.map { row ->
                val cells = Array(columns.size) { StringBuilder() }
                for (run in row) {
                    val col = assignCol(run)
                    if (cells[col].isNotEmpty()) cells[col].append(" ")
                    cells[col].append(run.text.trim())
                }
                cells.map { it.toString().trim() }
            }

            if (cellMatrix.isEmpty()) return null
            val (headers, dataRows) = extractHeadersAndData(cellMatrix)
            val markdown = buildMarkdownFromRows(listOf(headers) + dataRows)
            return Block.Table(markdown, headers, dataRows, TokenCounter.estimate(markdown))
        }

        private fun extractHeadersAndData(cellMatrix: List<List<String>>): Pair<List<String>, List<List<String>>> {
            if (cellMatrix.size < 3) return Pair(cellMatrix.first(), cellMatrix.drop(1))

            val row0 = cellMatrix[0]
            val row1 = cellMatrix[1]

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
                    nodeStack.removeLast()
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
