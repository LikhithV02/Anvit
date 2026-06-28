package com.anvit.localai.document

import com.anvit.pdfbridge.pdf_extract_layout
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSAttributedString
import platform.PDFKit.PDFDocument
import platform.UIKit.NSFontAttributeName
import platform.UIKit.UIFont

class IosPdfHierarchicalParser : DocumentParser {

    // Matches tokens that look like financial numbers: digits/commas/dots/parens/percent/dash
    private val financialNumRe = Regex("""^[\d,.()\-%]+$""")

    // Matches text that identifies page-footer / letter-header rows (generic across documents):
    //   - Registered office block trigger (legal requirement for listed companies)
    //   - Telefax line (second row of the contact block)
    //   - Auditor credential lines (CA membership, UDIN, ICAI registration)
    //   - Generic auditor signature "For [Firm] LLP" / "Chartered Accountants"
    //   - Indian exchange notification letter headers (BSE, NSE)
    //   - Indian company registration number (CIN)
    private val footerPhraseRe = Regex(
        """(Registered\s+(Office|Offic)|Corporate\s+Communications|Telefax|""" +
        """Membership\s+No|UDIN:|ICAI\s+Reg|Chartered\s+Accountants|""" +
        """BSE\s+Limited|National\s+Stock\s+Exchange|""" +
        """For\s+[A-Z].+\s+LLP\b|""" +
        """CIN\s+[A-Z]\d)""",
        RegexOption.IGNORE_CASE
    )

    private fun isFooterRow(row: LayoutRow): Boolean {
        val rowText = row.segs.joinToString(" ") { it.text }
        return footerPhraseRe.containsMatchIn(rowText)
    }

    override fun supports(fileName: String) = fileName.endsWith(".pdf", ignoreCase = true)

    // ──────────────────────────────────────────────────────────────────────────
    // Bridge: extract word-level bounding boxes from the PDF via Swift/PDFKit
    // ──────────────────────────────────────────────────────────────────────────

    private data class Segment(val x: Double, val w: Double, val text: String)
    private data class LayoutRow(val y: Double, val segs: List<Segment>)
    private data class PageLayout(val page: Int, val rows: List<LayoutRow>)

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun extractLayout(bytes: ByteArray): List<PageLayout> {
        val jsonChannel = Channel<String>(1)
        val stableRef = StableRef.create(jsonChannel)

        bytes.usePinned { pinned ->
            pdf_extract_layout(
                pdf_bytes = pinned.addressOf(0).reinterpret<UByteVar>(),
                byte_count = bytes.size,
                user_data = stableRef.asCPointer(),
                callback = staticCFunction { userData, jsonPtr ->
                    val ch = userData!!.asStableRef<Channel<String>>().get()
                    val json = jsonPtr?.toKString() ?: "[]"
                    ch.trySend(json)
                    ch.close()
                }
            )
        }

        return try {
            val json = jsonChannel.receive()
            stableRef.dispose()
            parseLayoutJson(json)
        } catch (_: Exception) {
            stableRef.dispose()
            emptyList()
        }
    }

    private fun parseLayoutJson(json: String): List<PageLayout> {
        return try {
            val arr = Json.parseToJsonElement(json) as? JsonArray ?: return emptyList()
            arr.map { pageEl ->
                val pageObj = pageEl.jsonObject
                val pageIdx = pageObj["page"]?.jsonPrimitive?.double?.toInt() ?: 0
                val rows = pageObj["rows"]?.jsonArray?.map { rowEl ->
                    val rowObj = rowEl.jsonObject
                    val y = rowObj["y"]?.jsonPrimitive?.double ?: 0.0
                    val segs = rowObj["segs"]?.jsonArray?.map { segEl ->
                        val so = segEl.jsonObject
                        Segment(
                            x = so["x"]?.jsonPrimitive?.double ?: 0.0,
                            w = so["w"]?.jsonPrimitive?.double ?: 0.0,
                            text = so["t"]?.jsonPrimitive?.content ?: ""
                        )
                    } ?: emptyList()
                    LayoutRow(y, segs)
                } ?: emptyList()
                PageLayout(pageIdx, rows)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Column detection: 12pt X gap between segments = column boundary
    // ──────────────────────────────────────────────────────────────────────────

    private fun splitColumnsFromRow(row: LayoutRow): List<String> {
        if (row.segs.isEmpty()) return emptyList()
        val cols = mutableListOf<MutableList<Segment>>()
        var current = mutableListOf(row.segs[0])
        for (i in 1 until row.segs.size) {
            val prev = row.segs[i - 1]
            val curr = row.segs[i]
            val gap = curr.x - (prev.x + prev.w)
            if (gap >= 12.0) {
                cols.add(current)
                current = mutableListOf(curr)
            } else {
                current.add(curr)
            }
        }
        cols.add(current)
        return cols.map { segs -> segs.joinToString(" ") { it.text } }
    }

    // Returns true if the row contains a token that looks like a financial number.
    // Accepts: comma-formatted large numbers (1,234+), decimals/percentages (14.9, +34%),
    // currency-prefixed values ($9.3, ₹8,924), and signed/bracketed numbers ((0.3), -5.4).
    // Excludes: plain 4-digit numbers like phone numbers (3555, 5000) with no commas/decimals.
    private val commaNumRe = Regex("""^\d{1,3}(,\d{3})+(\.\d+)?$""")
    // Decimal/pct: must have at least one digit AFTER the "." to exclude item labels like "6."
    private val decimalNumRe = Regex("""^\(?\d+\.\d+%?\)?$""")
    private val currencyPrefixRe = Regex("""^[$₹€£¥]""")
    private fun rowHasLargeFinancialToken(row: LayoutRow): Boolean {
        for (seg in row.segs) {
            val raw = seg.text
            val t = raw.trimStart('$', '₹', '€', '£', '¥', '+').trimStart('(').trimEnd(')')
            if (commaNumRe.matches(t)) return true
            // Decimal or percentage: must have digit after "." (excludes list labels like "6.")
            if (decimalNumRe.matches(t)) return true
            if (raw.contains('%') && financialNumRe.matches(t)) return true
            // Currency-prefixed — the symbol confirms financial context
            if (currencyPrefixRe.containsMatchIn(raw) && financialNumRe.matches(t)) return true
        }
        return false
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Main parse
    // ──────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun parse(fileName: String, bytes: ByteArray): SectionNode =
        withContext(Dispatchers.Default) {
            val doc = PDFDocument(data = bytes.toNSData())
                ?: return@withContext SectionNode(title = fileName, level = 0)

            val baseFontSize = computeBaseFontSize(doc)

            val layoutByPage: Map<Int, PageLayout> = try {
                extractLayout(bytes).associateBy { it.page }
            } catch (_: Exception) {
                emptyMap()
            }

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
                val pageLayout = layoutByPage[i]

                if (pageLayout != null) {
                    parsePageWithLayout(
                        pageLayout, attrStr, baseFontSize,
                        nodeStack, currentListItems, pendingTextLines, listPrefixRegex,
                        ::flushList, ::flushTextBuffer
                    )
                } else {
                    parsePageFallback(
                        lines, attrStr, baseFontSize,
                        nodeStack, currentListItems, pendingTextLines, listPrefixRegex,
                        ::flushList, ::flushTextBuffer
                    )
                }

                flushTextBuffer()
            }

            flushTextBuffer()
            flushList()
            root
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Bridge-path page parser (pixel-accurate column detection)
    // ──────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalForeignApi::class)
    private fun parsePageWithLayout(
        pageLayout: PageLayout,
        attrStr: NSAttributedString,
        baseFontSize: Float,
        nodeStack: MutableList<SectionNode>,
        currentListItems: MutableList<String>,
        pendingTextLines: MutableList<String>,
        listPrefixRegex: Regex,
        flushList: () -> Unit,
        flushTextBuffer: () -> Unit
    ) {
        // Detect table ranges: contiguous multi-column runs (12pt gap) of ≥2 rows
        // that contain at least one row with a large financial token (comma-number or decimal/pct).
        // Single-column "bridge" rows (wrapped text like "Associates & JVs") are allowed inside
        // a run if the Y gap to the next multi-column row is < 40pt (≈2 line-heights).
        // Footer rows (registered office, auditor signatures, exchange headers) are excluded.
        val rows = pageLayout.rows.filterNot { isFooterRow(it) }
        val tableRanges = mutableListOf<IntRange>()
        var i = 0
        while (i < rows.size) {
            if (splitColumnsFromRow(rows[i]).size < 2) { i++; continue }
            var j = i + 1
            while (j < rows.size) {
                if (splitColumnsFromRow(rows[j]).size >= 2) { j++; continue }
                // Single-column row: allow as bridge only if next row is multi-column and Y gap < 40pt
                if (j + 1 < rows.size && splitColumnsFromRow(rows[j + 1]).size >= 2) {
                    val yGap = rows[j + 1].y - rows[j - 1].y
                    if (yGap < 40.0) { j++; continue }
                }
                break
            }
            // Trim trailing bridge (non-multi-column) rows
            var end = j
            while (end > i && splitColumnsFromRow(rows[end - 1]).size < 2) end--
            if (end - i >= 2) {
                val hasFinancial = (i until end).any { rowHasLargeFinancialToken(rows[it]) }
                if (hasFinancial) tableRanges.add(i until end)
            }
            i = j
        }

        val tableRowSet = tableRanges.flatMap { it.toList() }.toSet()
        val tablesByStart = tableRanges.associateBy { it.first }

        // Iterate over filtered rows (footer rows already excluded)
        var idx = 0
        while (idx < rows.size) {
            val tableRange = tablesByStart[idx]
            if (tableRange != null) {
                val tableRows = tableRange.map { rows[it] }
                val table = buildTableFromLayoutRows(tableRows)
                if (table != null) {
                    flushTextBuffer()
                    flushList()
                    nodeStack.last().blocks.add(table)
                }
                idx = tableRange.last + 1
                continue
            }
            if (idx in tableRowSet) { idx++; continue }

            val row = rows[idx]
            val trimmed = row.segs.joinToString(" ") { it.text }.trim()
            if (trimmed.isBlank()) { idx++; continue }

            val (fontSize, isBold) = getFontInfo(attrStr, trimmed, baseFontSize)

            if (listPrefixRegex.matches(trimmed)) {
                flushTextBuffer()
                currentListItems.add(trimmed)
                idx++
                continue
            } else {
                flushList()
            }

            val lineTokens = trimmed.split(Regex("\\s+"))
            val numericTokenCount = lineTokens.count { financialNumRe.matches(it) }
            val isNumericHeavy = numericTokenCount >= 2
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
                pendingTextLines.add(trimmed)
                if (pendingTextLines.sumOf { it.length } > 800) flushTextBuffer()
            }

            idx++
        }
    }

    private fun buildTableFromLayoutRows(rows: List<LayoutRow>): Block.Table? {
        // Skip single-column bridge rows and footer rows (address blocks, auditor signatures)
        var tableRows = rows.filterNot { isFooterRow(it) }.filter { splitColumnsFromRow(it).size >= 2 }
        // Trim trailing rows without financial tokens (footer continuation rows bridged in)
        while (tableRows.isNotEmpty() && !rowHasLargeFinancialToken(tableRows.last()))
            tableRows = tableRows.dropLast(1)
        val allCols = tableRows.map { splitColumnsFromRow(it) }.filter { it.isNotEmpty() }
        if (allCols.isEmpty()) return null
        val bulletRegex = Regex("^[•‣◦⁃∙oO*\\-–]$")
        // Reject if all rows start with a bullet (pure bullet list) or first row's first col is a bullet
        if (allCols.all { row -> row.firstOrNull()?.trim()?.let { bulletRegex.matches(it) } == true })
            return null
        if (allCols.firstOrNull()?.firstOrNull()?.trim()?.let { bulletRegex.matches(it) } == true)
            return null
        val (headers, dataRows) = extractHeadersAndData(allCols)
        val md = buildMarkdownFromRows(listOf(headers) + dataRows)
        return Block.Table(md, headers, dataRows, TokenCounter.estimate(md))
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun getFontInfo(
        attrStr: NSAttributedString,
        trimmed: String,
        baseFontSize: Float
    ): Pair<Float, Boolean> {
        val fullText = attrStr.string
        val lineStart = fullText.indexOf(trimmed)
        if (lineStart < 0) return Pair(baseFontSize, false)
        val attrs = attrStr.attributesAtIndex(lineStart.toULong(), null)
        val font = attrs[NSFontAttributeName] as? UIFont
        return Pair(
            font?.pointSize?.toFloat() ?: baseFontSize,
            font?.fontName?.contains("Bold", ignoreCase = true) ?: false
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Fallback page parser (whitespace heuristics, also used by simulator)
    // ──────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalForeignApi::class)
    private fun parsePageFallback(
        lines: List<String>,
        attrStr: NSAttributedString,
        baseFontSize: Float,
        nodeStack: MutableList<SectionNode>,
        currentListItems: MutableList<String>,
        pendingTextLines: MutableList<String>,
        listPrefixRegex: Regex,
        flushList: () -> Unit,
        flushTextBuffer: () -> Unit
    ) {
        val tableRanges = detectTableRanges(lines)
        val tableLineIndices = tableRanges.flatMap { it.toList() }.toSet()
        val tablesByStart = tableRanges.associateBy { it.first }

        var lineOffset = 0
        var lineIdx = 0
        while (lineIdx < lines.size) {
            val line = lines[lineIdx]
            val lineLen = line.length

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

            val lineTokens = trimmed.split(Regex("\\s+"))
            val numericTokenCount = lineTokens.count { financialNumRe.matches(it) }
            val isNumericHeavy = numericTokenCount >= 2
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
                pendingTextLines.add(trimmed)
                if (pendingTextLines.sumOf { it.length } > 800) flushTextBuffer()
            }

            lineOffset += lineLen + 1
            lineIdx++
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Table helpers (whitespace-heuristic path)
    // ──────────────────────────────────────────────────────────────────────────

    private fun isTableLine(line: String): Boolean {
        if (line.isBlank()) return false
        val trimmed = line.trim()
        if (trimmed.split(Regex("\\s{3,}")).count { it.isNotBlank() } >= 2) return true
        return isNumericGridLine(trimmed)
    }

    private fun isNumericGridLine(line: String): Boolean {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.size < 4) return false
        val numericCount = tokens.count { financialNumRe.matches(it) }
        return numericCount >= 3 && numericCount.toFloat() / tokens.size >= 0.4f
    }

    private fun parseTableColumns(line: String): List<String> {
        val trimmed = line.trim()
        val spaceSplit = trimmed.split(Regex("\\s{3,}"))
            .map { it.trim() }.filter { it.isNotBlank() }
        if (spaceSplit.size >= 2) return spaceSplit
        return parseNumericGridColumns(trimmed).filter { it.isNotBlank() }
    }

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
        val bulletRegex = Regex("^[•‣◦⁃∙oO*\\-–]$")
        if (allRows.all { row -> row.firstOrNull()?.trim()?.let { bulletRegex.matches(it) } == true })
            return null
        val (headers, dataRows) = extractHeadersAndData(allRows)
        val md = buildMarkdownFromRows(listOf(headers) + dataRows)
        return Block.Table(md, headers, dataRows, TokenCounter.estimate(md))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Shared: multi-row header merging and ghost-column removal
    // ──────────────────────────────────────────────────────────────────────────

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
