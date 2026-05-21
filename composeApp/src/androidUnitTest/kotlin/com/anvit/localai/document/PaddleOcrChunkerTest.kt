package com.anvit.localai.document

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaddleOcrChunkerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun chunksOcrJsonWithLeanContentAndMetadata() {
        val fixture = readFixture("24042026_Media_Release_RIL_Q4_FY2025-26_Financial_and_Operational_Performance.json")
        val parsed = PaddleOcrChunker(maxTextTokens = 240, maxTableTokens = 320).chunk(fixture.toDocument())

        assertTrue(parsed.chunks.isNotEmpty())
        assertTrue(parsed.chunks.any { it.chunkType == ChunkTypes.SECTION })
        assertTrue(parsed.chunks.any { it.chunkType == ChunkTypes.TEXT && it.parentChunkId != null })
        assertTrue(parsed.chunks.any { it.chunkType == ChunkTypes.TABLE || it.chunkType == ChunkTypes.TABLE_PART })
        assertTrue(parsed.chunks.any { it.pageStart > 0 && it.pageEnd >= it.pageStart && it.bboxJson.isNotBlank() })
        assertFalse(parsed.chunks.any { it.content.contains("Document >") })
    }

    @Test
    fun splitTablePartsRepeatHeadersWithoutSyntheticSummaries() {
        val page = PaddleOcrPage(
            page = 1,
            width = 1000,
            height = 1400,
            lines = buildList {
                add(line("FINANCIAL HIGHLIGHTS", 80, 80, 400, 110))
                val rows = listOf(
                    listOf("Sr. No", "Particulars", "4Q FY26", "FY26"),
                ) + (1..18).map { index ->
                    listOf(index.toString(), "Metric $index", "${index * 100}", "${index * 1000}")
                }
                rows.forEachIndexed { rowIndex, row ->
                    val y = 180 + rowIndex * 28
                    add(line(row[0], 80, y, 120, y + 18))
                    add(line(row[1], 180, y, 340, y + 18))
                    add(line(row[2], 520, y, 640, y + 18))
                    add(line(row[3], 780, y, 920, y + 18))
                }
            }
        )

        val chunks = PaddleOcrChunker(maxTableTokens = 60).chunk(PaddleOcrDocument("table.pdf", listOf(page))).chunks
        val parts = chunks.filter { it.chunkType == ChunkTypes.TABLE_PART }

        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.content.startsWith("| Sr. No | Particulars | 4Q FY26 | FY26 |") })
        assertFalse(parts.any { it.content.contains("summary", ignoreCase = true) })
        assertTrue(parts.all { it.rowRangeJson.isNotBlank() })
    }

    @Test
    fun mergesSmallProseChunksInSameSectionUpToTokenLimit() {
        val pages = listOf(
            PaddleOcrPage(
                page = 1,
                width = 1000,
                height = 1400,
                lines = listOf(
                    line("BUSINESS OVERVIEW", 260, 80, 740, 120),
                    line("Reliance expanded digital services during the year.", 80, 200, 720, 224)
                )
            ),
            PaddleOcrPage(
                page = 2,
                width = 1000,
                height = 1400,
                lines = listOf(
                    line("Retail growth was supported by wider store coverage.", 80, 200, 720, 224)
                )
            )
        )

        val chunks = PaddleOcrChunker(maxTextTokens = 80).chunk(PaddleOcrDocument("prose.pdf", pages)).chunks
        val mergedSection = chunks.single { it.chunkType == ChunkTypes.SECTION }

        assertTrue(mergedSection.content.contains("BUSINESS OVERVIEW"))
        assertTrue(mergedSection.content.contains("Reliance expanded digital services"))
        assertTrue(mergedSection.content.contains("Retail growth was supported"))
        assertTrue(mergedSection.pageStart == 1)
        assertTrue(mergedSection.pageEnd == 2)
    }

    @Test
    fun mergesSectionHeadingWithImmediateIntroTextInSameSection() {
        val page = PaddleOcrPage(
            page = 1,
            width = 1000,
            height = 1400,
            lines = listOf(
                line("CONSOLIDATED RESULTS FOR QUARTER / YEAR ENDED 31ST MARCH, 2026", 80, 80, 920, 120),
                line("Record annual consolidated revenue increased during the year.", 80, 180, 820, 204),
                line("CONSOLIDATED FINANCIAL HIGHLIGHTS", 180, 280, 820, 318),
                line("Sr. No", 80, 380, 130, 404),
                line("Particulars", 180, 380, 340, 404),
                line("FY26", 520, 380, 610, 404),
                line("1", 80, 420, 120, 444),
                line("Gross Revenue", 180, 420, 340, 444),
                line("100", 520, 420, 610, 444),
                line("2", 80, 460, 120, 484),
                line("EBITDA", 180, 460, 340, 484),
                line("20", 520, 460, 610, 484)
            )
        )

        val chunks = PaddleOcrChunker(maxTextTokens = 1500, maxTableTokens = 1500)
            .chunk(PaddleOcrDocument("intro.pdf", listOf(page)))
            .chunks

        assertTrue(chunks.first().chunkType == ChunkTypes.SECTION)
        assertTrue(chunks.first().content.contains("CONSOLIDATED RESULTS FOR QUARTER"))
        assertTrue(chunks.first().content.contains("Record annual consolidated revenue"))
        assertTrue(chunks.none { it.chunkType == ChunkTypes.TEXT && it.content.contains("Record annual consolidated revenue") })
        assertTrue(chunks.any { it.chunkType == ChunkTypes.TABLE })
    }

    @Test
    fun doesNotMergeListChunksWithNeighboringProse() {
        val page = PaddleOcrPage(
            page = 1,
            width = 1000,
            height = 1400,
            lines = listOf(
                line("ANNUAL PERFORMANCE", 260, 80, 740, 120),
                line("The company reported broad-based growth.", 80, 180, 720, 204),
                line("· Revenue increased across major businesses.", 80, 260, 760, 284),
                line("o Digital services grew with subscriber additions.", 110, 300, 760, 324),
                line("Margin expansion was supported by operating leverage.", 80, 390, 760, 414)
            )
        )

        val chunks = PaddleOcrChunker(maxTextTokens = 1500).chunk(PaddleOcrDocument("list.pdf", listOf(page))).chunks
        val listChunk = chunks.single { it.chunkType == ChunkTypes.TEXT && it.content.contains("Revenue increased across major businesses") }

        assertTrue(chunks.first().chunkType == ChunkTypes.SECTION)
        assertTrue(chunks.first().content.contains("The company reported broad-based growth."))
        assertFalse(chunks.first().content.contains("Revenue increased across major businesses"))
        assertTrue(listChunk.content.contains("- Revenue increased across major businesses"))
        assertTrue(listChunk.content.contains("  - Digital services grew with subscriber additions"))
        assertFalse(chunks.last().content.contains("Revenue increased across major businesses"))
    }

    @Test
    fun rilFirstPageFinancialHighlightsTableIsCompleteAndReadable() {
        val fixture = readFixture("24042026_Media_Release_RIL_Q4_FY2025-26_Financial_and_Operational_Performance.json")
        val firstPage = fixture.copy(pages = fixture.pages.take(1))
        val chunks = PaddleOcrChunker(maxTextTokens = 520, maxTableTokens = 900).chunk(firstPage.toDocument()).chunks
        val firstPageContent = chunks.joinToString("\n") { it.content }
        val tables = chunks.filter { it.chunkType == ChunkTypes.TABLE }
        val financialTable = tables.single { it.content.contains("Gross Revenue") && it.content.contains("Net Debt to EBITDA") }

        assertTrue(financialTable.content.startsWith("( in crore)\n| Sr. No | Particulars | 4Q FY26 | 3Q FY26 | 4Q FY25 | % chg. Y-o-Y | FY26 | FY25 |"))
        assertTrue(financialTable.content.contains("| 9 | Share of Profit/(Loss) of Associates & JVs |"))
        assertTrue(financialTable.content.contains("| 10 | Profit After Tax and Share of Profit/(Loss) of Associates & JVs |"))
        assertTrue(financialTable.content.contains("| 11 | Capital Expenditure# |"))
        assertTrue(financialTable.content.contains("| 15 | Net Debt to EBITDA* |"))
        assertFalse(firstPageContent.contains("Col 9"))
        assertFalse(firstPageContent.contains("Col 10"))
        assertFalse(chunks.any { it.chunkType == ChunkTypes.SECTION && it.content == "% chg." })
        assertFalse(chunks.any { it.chunkType == ChunkTypes.SECTION && it.content == "Capital Expenditure#" })
    }

    @Test
    fun rilSecondPageAnnualPerformanceKeepsCapturedBulletStructure() {
        val fixture = readFixture("24042026_Media_Release_RIL_Q4_FY2025-26_Financial_and_Operational_Performance.json")
        val secondPage = fixture.copy(pages = fixture.pages.drop(1).take(1))
        val chunks = PaddleOcrChunker(maxTextTokens = 520, maxTableTokens = 900).chunk(secondPage.toDocument()).chunks
        val annualPerformance = chunks.single { it.chunkType == ChunkTypes.TEXT && it.hierarchyPath.lastOrNull() == "Annual Performance" }

        assertTrue(annualPerformance.content.contains("- Gross Revenue increased by 9.8%"))
        assertTrue(annualPerformance.content.contains("  - Oil and Gas segment revenue decreased by 5.4%"))
        assertTrue(annualPerformance.content.contains("  - O2C EBITDA increased by 10.1%"))
        assertTrue(annualPerformance.content.contains("further aided by efficient feedstock sourcing"))
        assertTrue(annualPerformance.content.contains("[OCR continuation; preceding text may be missing]"))
        assertFalse(annualPerformance.content.contains("Registered Office"))
        assertFalse(annualPerformance.content.contains("Telephone"))
    }

    private fun readFixture(name: String): FixtureDocument {
        val file = File("../experiments/paddleocr_test/outputs/$name")
        require(file.exists()) { "Missing PaddleOCR fixture: ${file.absolutePath}" }
        return json.decodeFromString(FixtureDocument.serializer(), file.readText())
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

    private fun line(text: String, x1: Int, y1: Int, x2: Int, y2: Int): PaddleOcrLine =
        PaddleOcrLine(
            text = text,
            confidence = 0.99,
            box = listOf(
                OcrPoint(x1.toDouble(), y1.toDouble()),
                OcrPoint(x2.toDouble(), y1.toDouble()),
                OcrPoint(x2.toDouble(), y2.toDouble()),
                OcrPoint(x1.toDouble(), y2.toDouble())
            )
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
