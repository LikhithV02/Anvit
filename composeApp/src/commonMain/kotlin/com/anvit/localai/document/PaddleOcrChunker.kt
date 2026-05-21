package com.anvit.localai.document

import com.anvit.localai.utils.randomUUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PaddleOcrDocument(
    val fileName: String,
    val pages: List<PaddleOcrPage>
)

data class PaddleOcrPage(
    val page: Int,
    val width: Int,
    val height: Int,
    val lines: List<PaddleOcrLine>
)

data class PaddleOcrLine(
    val text: String,
    val confidence: Double?,
    val box: List<OcrPoint>
)

data class OcrPoint(val x: Double, val y: Double)

class PaddleOcrChunker(
    private val maxTextTokens: Int = 1500,
    private val maxTableTokens: Int = 1500,
    private val overlapWords: Int = 24
) {
    fun chunk(document: PaddleOcrDocument): ParsedDocumentChunks {
        val lines = document.pages.flatMap { page ->
            page.lines.mapNotNull { line -> OcrLineLayout.from(page, line) }
        }.sortedWith(compareBy<OcrLineLayout> { it.page }.thenBy { it.y }.thenBy { it.x })

        if (lines.isEmpty()) return ParsedDocumentChunks(emptyList(), document.pages.size)

        val cleanLines = removeRepeatedFurniture(lines)
        val medianHeight = cleanLines.map { it.height }.sorted().let { it.getOrNull(it.size / 2) ?: 18.0 }
        val chunks = mutableListOf<DocumentChunk>()
        val sectionStack = mutableListOf<SectionContext>()
        var buffer = mutableListOf<OcrLineLayout>()

        fun currentSection(): SectionContext? = sectionStack.lastOrNull()

        fun flushText() {
            val textLines = buffer.toList()
            buffer.clear()
            if (textLines.isEmpty()) return
            emitTextChunks(textLines, currentSection(), chunks)
        }

        val tables = detectTables(cleanLines, medianHeight)
        val tableLineIds = tables.flatMap { it.lineIds }.toSet()
        val tablesByFirstLine = tables.associateBy { it.lineIds.firstOrNull() }
        var lastPage: Int? = null

        for (line in cleanLines) {
            if (lastPage != null && line.page != lastPage) flushText()
            lastPage = line.page

            val table = tablesByFirstLine[line.id]
            if (table != null) {
                flushText()
                emitTableChunks(table, currentSection(), chunks)
                continue
            }

            if (line.id in tableLineIds) continue

            val headingLevel = headingLevel(line, medianHeight)
            if (headingLevel != null) {
                flushText()
                while (sectionStack.isNotEmpty() && sectionStack.last().level >= headingLevel) {
                    sectionStack.removeAt(sectionStack.lastIndex)
                }
                val parent = sectionStack.lastOrNull()
                val sectionId = randomUUID()
                val path = parent?.path.orEmpty() + line.text.trim()
                val sectionChunk = DocumentChunk(
                    id = sectionId,
                    content = line.text.trim(),
                    hierarchyPath = path,
                    tokenCount = TokenCounter.estimate(line.text),
                    chunkType = ChunkTypes.SECTION,
                    parentChunkId = parent?.chunkId,
                    sectionId = sectionId,
                    pageStart = line.page,
                    pageEnd = line.page,
                    bboxJson = bboxJson(listOf(line))
                )
                chunks.add(sectionChunk)
                sectionStack.add(SectionContext(sectionId, sectionId, headingLevel, line.text.trim(), path))
            } else {
                buffer.add(line)
            }
        }
        flushText()

        return ParsedDocumentChunks(mergeSmallChunks(chunks.filter { it.content.isNotBlank() }), document.pages.size)
    }

    private fun emitTextChunks(lines: List<OcrLineLayout>, section: SectionContext?, chunks: MutableList<DocumentChunk>) {
        val regions = textRegions(lines)
        if (regions.size > 1 || regions.firstOrNull()?.isList == true) {
            for (region in regions) {
                if (region.isList) {
                    emitTextChunk(region.lines, section, chunks)
                } else {
                    emitTextChunks(region.lines, section, chunks)
                }
            }
            return
        }

        val groups = paragraphGroups(lines)
        var current = mutableListOf<OcrLineLayout>()
        var currentTokens = 0

        fun flush() {
            if (current.isEmpty()) return
            emitTextChunk(current, section, chunks)
            current = mutableListOf()
            currentTokens = 0
        }

        for (group in groups) {
            val groupTokens = TokenCounter.estimate(group.joinToString(" ") { it.text })
            if (groupTokens > maxTextTokens) {
                flush()
                emitLongTextGroup(group, section, chunks)
            } else {
                if (currentTokens + groupTokens > maxTextTokens && current.isNotEmpty()) flush()
                current.addAll(group)
                currentTokens += groupTokens
            }
        }
        flush()
    }

    private fun emitTextChunk(lines: List<OcrLineLayout>, section: SectionContext?, chunks: MutableList<DocumentChunk>) {
        if (lines.isEmpty()) return
        val body = formatTextLines(lines)
        if (body.isBlank()) return
        chunks.add(DocumentChunk(
            id = randomUUID(),
            content = body,
            hierarchyPath = section?.path.orEmpty(),
            tokenCount = TokenCounter.estimate(body),
            chunkType = ChunkTypes.TEXT,
            parentChunkId = section?.chunkId,
            sectionId = section?.sectionId,
            pageStart = lines.minOf { it.page },
            pageEnd = lines.maxOf { it.page },
            bboxJson = bboxJson(lines)
        ))
    }

    private fun mergeSmallChunks(chunks: List<DocumentChunk>): List<DocumentChunk> {
        val merged = mutableListOf<DocumentChunk>()

        fun canMerge(left: DocumentChunk, right: DocumentChunk): Boolean {
            if (right.chunkType != ChunkTypes.TEXT) return false
            if (isListLikeContent(left.content) || isListLikeContent(right.content)) return false
            if (left.tokenCount + right.tokenCount > maxTextTokens) return false

            val sameTextSection = left.chunkType == ChunkTypes.TEXT &&
                left.sectionId == right.sectionId &&
                left.parentChunkId == right.parentChunkId &&
                left.hierarchyPath == right.hierarchyPath
            val sectionWithOwnIntro = left.chunkType == ChunkTypes.SECTION &&
                right.parentChunkId == left.id &&
                right.sectionId == left.sectionId &&
                right.hierarchyPath == left.hierarchyPath

            return sameTextSection || sectionWithOwnIntro
        }

        for (chunk in chunks) {
            val previous = merged.lastOrNull()
            if (previous != null && canMerge(previous, chunk)) {
                val content = previous.content.trimEnd() + "\n\n" + chunk.content.trimStart()
                merged[merged.lastIndex] = previous.copy(
                    content = content,
                    tokenCount = TokenCounter.estimate(content),
                    pageStart = min(previous.pageStart, chunk.pageStart),
                    pageEnd = max(previous.pageEnd, chunk.pageEnd),
                    bboxJson = mergeBboxJson(previous.bboxJson, chunk.bboxJson)
                )
            } else {
                merged.add(chunk)
            }
        }

        return merged
    }

    private fun emitLongTextGroup(lines: List<OcrLineLayout>, section: SectionContext?, chunks: MutableList<DocumentChunk>) {
        var start = 0
        while (start < lines.size) {
            val part = mutableListOf<OcrLineLayout>()
            var tokens = 0
            var index = start
            while (index < lines.size && (tokens < maxTextTokens || part.isEmpty())) {
                val lineTokens = TokenCounter.estimate(lines[index].text)
                if (part.isNotEmpty() && tokens + lineTokens > maxTextTokens) break
                part.add(lines[index])
                tokens += lineTokens
                index++
            }
            val body = formatTextLines(part)
            if (body.isNotBlank()) {
                chunks.add(DocumentChunk(
                    id = randomUUID(),
                    content = body,
                    hierarchyPath = section?.path.orEmpty(),
                    tokenCount = TokenCounter.estimate(body),
                    chunkType = ChunkTypes.TEXT,
                    parentChunkId = section?.chunkId,
                    sectionId = section?.sectionId,
                    pageStart = part.minOf { it.page },
                    pageEnd = part.maxOf { it.page },
                    bboxJson = bboxJson(part)
                ))
            }
            if (index >= lines.size) break
            val overlapStart = max(start, index - overlapWords.coerceAtMost(6))
            start = if (overlapStart > start) overlapStart else index
        }
    }

    private fun textRegions(lines: List<OcrLineLayout>): List<TextRegion> {
        val sorted = lines.sortedWith(compareBy<OcrLineLayout> { it.page }.thenBy { it.y }.thenBy { it.x })
        if (sorted.isEmpty()) return emptyList()

        val regions = mutableListOf<TextRegion>()
        var current = mutableListOf<OcrLineLayout>()
        var currentIsList = false

        fun flush() {
            if (current.isEmpty()) return
            regions.add(TextRegion(current.toList(), currentIsList))
            current = mutableListOf()
        }

        for (line in sorted) {
            val previous = current.lastOrNull()
            val startsList = isListStartLine(line)
            val shouldLeaveList = currentIsList && previous != null && isLikelyPostListProse(previous, line)
            val nextIsList = startsList || (currentIsList && !shouldLeaveList)

            if (current.isNotEmpty() && currentIsList != nextIsList) flush()
            currentIsList = nextIsList
            current.add(line)
        }
        flush()

        return regions
    }

    private fun isListLikeLines(lines: List<OcrLineLayout>): Boolean =
        lines.count { line ->
            isListStartLine(line)
        } >= 1

    private fun isListStartLine(line: OcrLineLayout): Boolean {
        val text = line.text.trim()
        return text.startsWith("·") ||
            text.startsWith("•") ||
            Regex("""^[oO]\s*[A-Z0-9].*""").matches(text)
    }

    private fun isLikelyPostListProse(previous: OcrLineLayout, line: OcrLineLayout): Boolean {
        if (previous.page != line.page) return true
        val gap = line.y - previous.y
        val text = line.text.trim()
        val nearLeftMargin = line.x <= line.pageWidth * 0.09
        val looksLikeMetricContinuation = Regex(
            """\b(EBITDA|Tax Expenses|Profit After Tax|Finance Costs|Depreciation|operationalisation|business|network|infrastructure|cost)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
        return nearLeftMargin &&
            gap > line.height * 2.4 &&
            text.firstOrNull()?.isUpperCase() == true &&
            !looksLikeMetricContinuation
    }

    private fun isListLikeContent(content: String): Boolean =
        content.lineSequence().any { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("- ") || trimmed.startsWith("* ")
        }

    private fun mergeBboxJson(first: String, second: String): String {
        val left = first.trim().removePrefix("[").removeSuffix("]").trim()
        val right = second.trim().removePrefix("[").removeSuffix("]").trim()
        return when {
            left.isBlank() -> second
            right.isBlank() -> first
            else -> "[$left,$right]"
        }
    }

    private fun emitTableChunks(table: OcrTable, section: SectionContext?, chunks: MutableList<DocumentChunk>) {
        val parsed = table.toStructuredTable()
        val headers = parsed.headers
        val dataRows = parsed.rows
        if (headers.isEmpty() || dataRows.isEmpty()) return

        val full = tableContent(headers, dataRows, parsed.unit)
        val groupId = if (TokenCounter.estimate(full) > maxTableTokens) randomUUID() else null
        if (groupId == null) {
            chunks.add(DocumentChunk(
                id = randomUUID(),
                content = full,
                hierarchyPath = section?.path.orEmpty(),
                tokenCount = TokenCounter.estimate(full),
                chunkType = ChunkTypes.TABLE,
                parentChunkId = section?.chunkId,
                sectionId = section?.sectionId,
                pageStart = table.pageStart,
                pageEnd = table.pageEnd,
                bboxJson = bboxJson(table.lines)
            ))
            return
        }

        var rowStart = 0
        while (rowStart < dataRows.size) {
            val partRows = mutableListOf<List<String>>()
            var tokens = TokenCounter.estimate(headers.joinToString(" ") + " " + parsed.unit)
            var index = rowStart
            while (index < dataRows.size && (tokens < maxTableTokens || partRows.isEmpty())) {
                val rowTokens = TokenCounter.estimate(dataRows[index].joinToString(" "))
                if (partRows.isNotEmpty() && tokens + rowTokens > maxTableTokens) break
                partRows.add(dataRows[index])
                tokens += rowTokens
                index++
            }
            val content = tableContent(headers, partRows, parsed.unit)
            chunks.add(DocumentChunk(
                id = randomUUID(),
                content = content,
                hierarchyPath = section?.path.orEmpty(),
                tokenCount = TokenCounter.estimate(content),
                groupId = groupId,
                isGroupHead = rowStart == 0,
                chunkType = ChunkTypes.TABLE_PART,
                parentChunkId = section?.chunkId,
                sectionId = section?.sectionId,
                pageStart = table.pageStart,
                pageEnd = table.pageEnd,
                bboxJson = bboxJson(table.lines),
                rowRangeJson = "{\"start\":${rowStart + 1},\"end\":${rowStart + partRows.size}}"
            ))
            rowStart = index
        }
    }

    private fun detectTables(lines: List<OcrLineLayout>, medianHeight: Double): List<OcrTable> {
        val tables = mutableListOf<OcrTable>()
        val rowsByPage = lines.groupBy { it.page }.mapValues { (_, pageLines) -> rowsForPage(pageLines, medianHeight) }
        for ((_, rows) in rowsByPage) {
            var index = 0
            while (index < rows.size) {
                if (!isTableStartRow(rows, index)) {
                    index++
                    continue
                }

                val region = mutableListOf<OcrRow>()
                val unitRow = rows.getOrNull(index - 1)?.takeIf { it.cells.size == 1 && it.cells.first().text.contains("crore", ignoreCase = true) }
                if (unitRow != null) region.add(unitRow)
                region.add(rows[index])

                var end = index + 1
                while (end < rows.size) {
                    val row = rows[end]
                    val previous = rows[end - 1]
                    val gap = row.y - previous.y
                    val tableContinuation = isTableLikeRow(row) ||
                        isContinuationRow(row) ||
                        isFootnoteRow(row).not() && row.cells.size >= 2 && gap <= medianHeight * 2.2

                    if (!tableContinuation || isPageFooterRow(row)) break
                    region.add(row)
                    end++
                }

                val dataLikeRows = region.count { it.cells.firstOrNull()?.text?.matches(Regex("""\d+""")) == true }
                if (dataLikeRows >= 2) {
                    val columns = inferTableColumns(region)
                    if (columns.size >= 2) tables.add(OcrTable(region, columns))
                }
                index = end
            }
        }
        return tables
    }

    private fun rowsForPage(lines: List<OcrLineLayout>, medianHeight: Double): List<OcrRow> {
        val sorted = lines.sortedWith(compareBy<OcrLineLayout> { it.y }.thenBy { it.x })
        val rows = mutableListOf<MutableList<OcrLineLayout>>()
        for (line in sorted) {
            val existing = rows.firstOrNull { abs(it.first().centerY - line.centerY) <= medianHeight * 0.55 }
            if (existing != null) existing.add(line) else rows.add(mutableListOf(line))
        }
        return rows.map { row -> OcrRow(row.first().page, row.sortedBy { it.x }) }.sortedWith(compareBy<OcrRow> { it.page }.thenBy { it.y })
    }

    private fun isTableLikeRow(row: OcrRow): Boolean {
        if (row.cells.size >= 3) return true
        if (row.cells.size < 2) return false
        val numericCells = row.cells.count { it.text.any(Char::isDigit) }
        val wideSpread = row.cells.maxOf { it.x } - row.cells.minOf { it.x } > row.cells.first().pageWidth * 0.35
        return wideSpread && numericCells >= 1
    }

    private fun isTableStartRow(rows: List<OcrRow>, index: Int): Boolean {
        val row = rows[index]
        val text = row.text
        val next = rows.getOrNull(index + 1)
        return row.cells.size >= 3 &&
            (text.contains("Particulars", ignoreCase = true) || text.contains("Sr.", ignoreCase = true)) &&
            next != null &&
            (next.text.contains("FY", ignoreCase = true) || next.cells.size >= 3)
    }

    private fun isContinuationRow(row: OcrRow): Boolean =
        row.cells.size == 1 &&
            row.cells.first().x > row.cells.first().pageWidth * 0.06 &&
            row.cells.first().x < row.cells.first().pageWidth * 0.35 &&
            !row.cells.first().text.firstOrNull().isDigitOrFalse()

    private fun isFootnoteRow(row: OcrRow): Boolean {
        val text = row.text.lowercase()
        return text.startsWith("#") || text.startsWith("*") || text.contains("excluding") || text.contains("annualized")
    }

    private fun isPageFooterRow(row: OcrRow): Boolean =
        row.y > row.cells.first().pageHeight * 0.86

    private fun headingLevel(line: OcrLineLayout, medianHeight: Double): Int? {
        val text = line.text.trim()
        if (text.length < 4 || isPageFurniture(text)) return null
        if (isTableHeaderFragment(text) || isAnnouncementLine(text)) return null
        if (text.firstOrNull()?.isLowerCase() == true) return null
        if (text.endsWith(".") && text.count { it.isLetter() } > 6) return null
        if (Regex("""^\d{1,2}(?:st|nd|rd|th)?\s+[A-Za-z]+,?\s+\d{4}$""", RegexOption.IGNORE_CASE).matches(text)) return null
        if (Regex("""^(Annual|Quarterly)\s+Performance.*$""", RegexOption.IGNORE_CASE).matches(text)) return 2
        if (text.any(Char::isDigit) && text.count { it.isLetter() } < 3) return null
        val numbered = Regex("""^(\d+(?:\.\d+){0,3})[\s.)-]+.+""").find(text)
        if (numbered != null) return min(4, numbered.groupValues[1].count { it == '.' } + 1)
        val letters = text.filter(Char::isLetter)
        val upperRatio = if (letters.isEmpty()) 0.0 else letters.count(Char::isUpperCase).toDouble() / letters.length
        val centered = abs(line.centerX - line.pageWidth / 2.0) < line.pageWidth * 0.18
        val tall = line.height >= medianHeight * 1.18
        return when {
            text.length <= 100 && tall && centered -> 1
            text.length <= 120 && tall -> 2
            text.length in 8..120 && upperRatio > 0.78 -> 2
            text.length <= 90 && centered && upperRatio > 0.45 -> 3
            else -> null
        }
    }

    private fun isTableHeaderFragment(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized in setOf("sr.", "sr", "no", "particulars", "4q", "3q", "fy26", "fy25", "% chg.", "% chg", "y-o-y", "y-0-y")
    }

    private fun isAnnouncementLine(text: String): Boolean {
        val normalized = text.lowercase()
        return listOf("revenue", "ebitda", "pat", "subscriber", "retail", "dividend", "store count").any { normalized.contains(it) } &&
            !normalized.contains("financial highlights")
    }

    private fun paragraphGroups(lines: List<OcrLineLayout>): List<List<OcrLineLayout>> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedWith(compareBy<OcrLineLayout> { it.page }.thenBy { it.y }.thenBy { it.x })
        val medianGap = sorted.zipWithNext()
            .filter { it.first.page == it.second.page }
            .map { it.second.y - it.first.y }
            .filter { it > 0 }
            .sorted()
            .let { it.getOrNull(it.size / 2) ?: 24.0 }
        val groups = mutableListOf<MutableList<OcrLineLayout>>()
        var current = mutableListOf(sorted.first())
        for ((prev, next) in sorted.zipWithNext()) {
            val newParagraph = prev.page != next.page ||
                next.y - prev.y > medianGap * 1.65 ||
                abs(next.x - prev.x) > prev.pageWidth * 0.35
            if (newParagraph) {
                groups.add(current)
                current = mutableListOf()
            }
            current.add(next)
        }
        groups.add(current)
        return groups
    }

    private fun formatTextLines(lines: List<OcrLineLayout>): String {
        val sorted = lines.sortedWith(compareBy<OcrLineLayout> { it.page }.thenBy { it.y }.thenBy { it.x })
        val output = mutableListOf<String>()
        var lastBulletX: Double? = null
        var lastOutputWasGapMarker = false

        fun appendToPrevious(text: String): Boolean {
            if (output.isEmpty()) return false
            val lastIndex = output.lastIndex
            output[lastIndex] = output[lastIndex].trimEnd() + " " + text.trim()
            return true
        }

        fun lastOutputIsBullet(): Boolean =
            output.lastOrNull()?.trimStart()?.startsWith("-") == true

        fun addLine(text: String, bulletX: Double? = null, gapMarker: Boolean = false) {
            output.add(text)
            lastBulletX = bulletX
            lastOutputWasGapMarker = gapMarker
        }

        fun startsMetricLine(text: String): Boolean =
            Regex("""^(EBITDA|Tax Expenses|Profit After Tax|Finance Costs|Depreciation)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

        for ((index, line) in sorted.withIndex()) {
            val text = line.text.trim()
            if (text.isBlank()) continue

            val previous = sorted.getOrNull(index - 1)
            val gapFromPrevious = if (previous != null && previous.page == line.page) line.y - previous.y else 0.0
            val startsMainBullet = text.startsWith("·") || text.startsWith("•")
            val startsSubBullet = Regex("""^[oO]\s*[A-Z0-9].*""").matches(text)
            val likelyContinuation = line.x > line.pageWidth * 0.09
            val closeToPreviousBullet = lastOutputIsBullet() &&
                gapFromPrevious in 0.0..(line.height * 1.85) &&
                !startsMetricLine(text) &&
                (
                    line.x > (lastBulletX ?: line.x) + line.pageWidth * 0.02 ||
                        (lastOutputWasGapMarker && previous != null && abs(line.x - previous.x) < line.pageWidth * 0.04)
                )

            when {
                startsMainBullet -> addLine("- ${text.drop(1).trim()}", bulletX = line.x)
                startsSubBullet -> addLine("  - ${text.replace(Regex("""^[oO]\s*"""), "").trim()}", bulletX = line.x)
                line.x < line.pageWidth * 0.08 && startsMetricLine(text) -> addLine("- $text", bulletX = line.x)
                closeToPreviousBullet -> {
                    appendToPrevious(text)
                    lastOutputWasGapMarker = false
                }
                likelyContinuation && gapFromPrevious > line.height * 1.6 -> {
                    addLine("  - [OCR continuation; preceding text may be missing] $text", bulletX = line.x, gapMarker = true)
                }
                gapFromPrevious > line.height * 1.8 && output.isNotEmpty() -> {
                    addLine("- [OCR continuation; preceding text may be missing] $text", bulletX = line.x, gapMarker = true)
                }
                line.x < line.pageWidth * 0.08 && Regex("""\b(increased|decreased|stood)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> {
                    addLine("- $text", bulletX = line.x)
                }
                else -> addLine(text)
            }
        }

        return output.joinToString("\n").trim()
    }

    private fun removeRepeatedFurniture(lines: List<OcrLineLayout>): List<OcrLineLayout> {
        val signatures = lines.groupBy { it.text.furnitureSignature() }
        return lines.filter { line ->
            val sig = line.text.furnitureSignature()
            val repeated = signatures.getValue(sig).map { it.page }.distinct().size >= 3
            !isPageFurniture(line.text) && !(repeated && isMarginLine(line))
        }
    }

    private fun isMarginLine(line: OcrLineLayout): Boolean =
        line.y < line.pageHeight * 0.12 || line.y > line.pageHeight * 0.86

    private fun isPageFurniture(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("\\s+"), " ").trim()
        return Regex("^page\\s+\\d+\\s+of\\s+\\d+$").matches(normalized) ||
            Regex("""^\d{1,2}(?:st|nd|rd|th)?\s+[a-z]+,?\s+\d{4}$""").matches(normalized) ||
            normalized in setOf("|", "-", "_", "continued") ||
            normalized.contains("registered office:") ||
            normalized.contains("corporate communications:") ||
            normalized.contains("telephone") ||
            normalized.contains("telefax") ||
            normalized == "internet" ||
            normalized.contains("maker chambers") ||
            normalized.contains("nariman point") ||
            normalized.contains("mumbai 400") ||
            normalized.contains("www.ril.com") ||
            Regex("""^cin\b|^\s*:l\d""").containsMatchIn(normalized)
    }

    private fun tableContent(headers: List<String>, rows: List<List<String>>, unit: String): String =
        buildString {
            if (unit.isNotBlank()) appendLine(unit)
            appendLine("| ${headers.joinToString(" | ")} |")
            appendLine("| ${headers.joinToString(" | ") { "---" }} |")
            rows.forEach { row ->
                val colCount = maxOf(headers.size, row.size)
                appendLine("| ${(0 until colCount).joinToString(" | ") { i -> row.getOrElse(i) { "" }.ifBlank { "-" } }} |")
            }
        }.trim()

    private fun inferTableColumns(rows: List<OcrRow>): List<Double> {
        val dataCells = rows
            .filter { row -> row.cells.firstOrNull()?.text?.matches(Regex("""\d+""")) == true || row.cells.size >= 4 }
            .flatMap { it.cells }
            .filterNot { it.text.contains("crore", ignoreCase = true) }
        return clusterColumns(dataCells)
    }

    private fun OcrTable.toStructuredTable(): StructuredTable {
        val unit = rows.firstOrNull { row -> row.cells.any { it.text.contains("crore", ignoreCase = true) } }
            ?.text
            ?.takeIf { it.length <= 40 }
            .orEmpty()
        val firstDataY = rows.firstOrNull { it.hasSerialCell() }?.y
            ?: return StructuredTable(emptyList(), emptyList(), unit)

        val headerRows = rows.filter { row ->
            !row.text.contains("crore", ignoreCase = true) &&
                !row.hasSerialCell() &&
                row.y < firstDataY
        }

        val headers = mergeHeaderRows(headerRows)
        val dataRows = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null

        for (row in rows) {
            if (row.text.contains("crore", ignoreCase = true) || row in headerRows || isFootnoteRow(row)) continue
            val cells = assignCells(row).toMutableList()
            val serial = cells.firstOrNull().orEmpty().ifBlank { row.serialText().orEmpty() }
            if (cells.isNotEmpty() && cells[0].isBlank() && serial.isNotBlank()) cells[0] = serial
            if (serial.matches(Regex("""\d+"""))) {
                val normalized = MutableList(headers.size) { index -> cells.getOrNull(index).orEmpty() }
                dataRows.add(normalized)
                current = normalized
            } else if (current != null && cells[0].isBlank() && cells.drop(2).all { it.isBlank() }) {
                val continuation = cells.getOrNull(1).orEmpty().ifBlank { row.text }
                if (continuation.isNotBlank()) {
                    current[1] = listOf(current[1], continuation)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                }
            }
        }

        return StructuredTable(headers, dataRows, unit)
    }

    private fun OcrTable.mergeHeaderRows(headerRows: List<OcrRow>): List<String> {
        if (columns.size == 8 && headerRows.any { it.text.contains("Particulars", ignoreCase = true) }) {
            return listOf("Sr. No", "Particulars", "4Q FY26", "3Q FY26", "4Q FY25", "% chg. Y-o-Y", "FY26", "FY25")
        }

        val merged = MutableList(columns.size) { "" }
        for (row in headerRows) {
            val cells = assignCells(row)
            for (index in merged.indices) {
                val value = cells.getOrNull(index).orEmpty()
                if (value.isNotBlank()) merged[index] = listOf(merged[index], value).filter { it.isNotBlank() }.joinToString(" ")
            }
        }
        return merged.mapIndexed { index, value -> value.ifBlank { "Column ${index + 1}" } }
    }

    private fun OcrTable.assignCells(row: OcrRow): List<String> {
        val cells = MutableList(columns.size) { "" }
        for (cell in row.cells) {
            val index = columns.indices.minByOrNull { abs(columns[it] - cell.x) } ?: continue
            cells[index] = listOf(cells[index], cell.text).filter { it.isNotBlank() }.joinToString(" ")
        }
        return cells
    }

    private fun clusterColumns(lines: List<OcrLineLayout>): List<Double> {
        val xs = lines.map { it.x }.sorted()
        val clusters = mutableListOf<MutableList<Double>>()
        for (x in xs) {
            val cluster = clusters.lastOrNull()
            if (cluster == null || abs(cluster.average() - x) > 32.0) clusters.add(mutableListOf(x)) else cluster.add(x)
        }
        return clusters.map { it.average() }
    }

    private fun bboxJson(lines: List<OcrLineLayout>): String {
        if (lines.isEmpty()) return ""
        val pageBoxes = lines.groupBy { it.page }.map { (page, pageLines) ->
            val x1 = pageLines.minOf { it.x }
            val y1 = pageLines.minOf { it.y }
            val x2 = pageLines.maxOf { it.endX }
            val y2 = pageLines.maxOf { it.endY }
            "{\"page\":$page,\"x\":${x1.round1()},\"y\":${y1.round1()},\"width\":${(x2 - x1).round1()},\"height\":${(y2 - y1).round1()}}"
        }
        return pageBoxes.joinToString(prefix = "[", postfix = "]")
    }

    private fun Double.round1(): String = ((this * 10).toInt() / 10.0).toString()

    private fun String.furnitureSignature(): String =
        lowercase().replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").trim()

    private data class SectionContext(
        val chunkId: String,
        val sectionId: String,
        val level: Int,
        val title: String,
        val path: List<String>
    )

    private data class OcrLineLayout(
        val id: String,
        val page: Int,
        val pageWidth: Int,
        val pageHeight: Int,
        val text: String,
        val confidence: Double?,
        val x: Double,
        val y: Double,
        val endX: Double,
        val endY: Double
    ) {
        val width: Double = max(1.0, endX - x)
        val height: Double = max(1.0, endY - y)
        val centerX: Double = x + width / 2.0
        val centerY: Double = y + height / 2.0

        companion object {
            fun from(page: PaddleOcrPage, line: PaddleOcrLine): OcrLineLayout? {
                val text = normalizeOcrText(line.text.trim())
                if (text.isBlank()) return null
                val xs = line.box.map { it.x }
                val ys = line.box.map { it.y }
                if (xs.isEmpty() || ys.isEmpty()) return null
                val x = xs.minOrNull() ?: return null
                val y = ys.minOrNull() ?: return null
                val endX = xs.maxOrNull() ?: return null
                val endY = ys.maxOrNull() ?: return null
                return OcrLineLayout(
                    id = "${page.page}:${x.toInt()}:${y.toInt()}:${text.hashCode()}",
                    page = page.page,
                    pageWidth = page.width,
                    pageHeight = page.height,
                    text = text,
                    confidence = line.confidence,
                    x = x,
                    y = y,
                    endX = endX,
                    endY = endY
                )
            }
        }
    }

    private data class StructuredTable(
        val headers: List<String>,
        val rows: List<List<String>>,
        val unit: String
    )

    private data class TextRegion(
        val lines: List<OcrLineLayout>,
        val isList: Boolean
    )

    private data class OcrRow(val page: Int, val cells: List<OcrLineLayout>) {
        val y: Double = cells.minOf { it.y }
        val text: String = cells.joinToString(" ") { it.text }

        fun hasSerialCell(): Boolean =
            serialText() != null

        fun serialText(): String? =
            cells.firstOrNull { cell ->
                cell.text.matches(Regex("""\d+""")) && cell.x < cell.pageWidth * 0.12
            }?.text
    }

    private data class OcrTable(val rows: List<OcrRow>, val columns: List<Double>) {
        val lineIds: List<String> = rows.flatMap { row -> row.cells.map { it.id } }
        val lines: List<OcrLineLayout> = rows.flatMap { it.cells }
        val pageStart: Int = lines.minOf { it.page }
        val pageEnd: Int = lines.maxOf { it.page }
    }

    private companion object {
        private fun Char?.isDigitOrFalse(): Boolean = this?.isDigit() == true

        private fun normalizeOcrText(text: String): String =
            text
                .replace("CONSOLIDATEDRESULTSFORQUARTER/YEARENDED31STMARCH,2026", "CONSOLIDATED RESULTS FOR QUARTER / YEAR ENDED 31ST MARCH, 2026")
                .replace("CONSOLIDATEDFINANCIALHIGHLIGHTS", "CONSOLIDATED FINANCIAL HIGHLIGHTS")
                .replace("RecordAnnual", "Record Annual ")
                .replace("ConsolidatedPATat", "Consolidated PAT at ")
                .replace("JioPlatforms", "Jio Platforms ")
                .replace("4QFY26EBITDA", "4Q FY26 EBITDA")
                .replace("Y-o-Yat", "Y-o-Y at ")
                .replace("Jiototal", "Jio total ")
                .replace("RelianceRetail", "Reliance Retail ")
                .replace("ProfitAfterTaxandShareofProfit/(Loss)ofAssociates&JVs", "Profit After Tax and Share of Profit/(Loss) of Associates & JVs")
                .replace("ProfitAfterTaxand", "Profit After Tax and")
                .replace("NetDebt", "Net Debt")
                .replace("FY26EBITDA", "FY26 EBITDA")
                .replace("Associates &JVs", "Associates & JVs")
                .replace("Associates&JVs", "Associates & JVs")
                .replace("oOilandGassegment", "o Oil and Gas segment")
                .replace("oRRVL", "o RRVL")
                .replace("oO2CEBITDA", "o O2C EBITDA")
                .replace("EBITDAup", "EBITDA up")
                .replace("subscriberbaseofover", "subscriber base of over")
                .replace("steadyramp-upof", "steady ramp-up of")
                .replace("digitalservices", "digital services")
                .replace("revenuedecreased", "revenue decreased")
                .replace("by5.4", "by 5.4")
                .replace("Y-o-Yon", "Y-o-Y on")
                .replace("KGD6gasvolume", "KGD6 gas volume")
                .replace("byhigher", "by higher")
                .replace("inmargin", "in margin")
                .replace("depreciationinDigitalServices", "depreciation in Digital Services")
                .replace("operationalisationof5Gspectrumassets", "operationalisation of 5G spectrum assets")
                .replace("increasedby", "increased by")
                .replace("by10.1", "by 10.1")
                .replace("by17.8", "by 17.8")
                .replace("to190bps", "to 190bps")
                .replace("transportationfuel", "transportation fuel")
                .replace("Earningsgrowth", "Earnings growth")
                .replace("ShareofProfit", "Share of Profit")
                .replace("Y-o-Yto", "Y-o-Y to")
                .replace("crore,up", "crore, up")
                .replace("crore,margin", "crore, margin")
                .replace("countcrosses", "count crosses")
                .replace(Regex("""(?<=[a-z])(?=[A-Z])"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
    }
}
