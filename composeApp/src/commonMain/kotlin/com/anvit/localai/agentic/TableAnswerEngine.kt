package com.anvit.localai.agentic

import com.anvit.localai.retrieval.RetrievedChunk
import com.anvit.localai.utils.formatFixed
import kotlin.math.abs

data class TableAnswerResult(
    val answer: String?,
    val facts: String,
    val confidence: TableAnswerConfidence
)

enum class TableAnswerConfidence { NONE, MEDIUM, HIGH }

/**
 * Deterministic helper for structured table chunks emitted by HierarchicalChunker.
 *
 * It intentionally works on the current chunk text format so it can improve both
 * existing eval reports and runtime answers without a database migration.
 */
object TableAnswerEngine {
    private const val MAX_FACT_ROWS = 5
    private val stopWords = setOf(
        "what", "which", "where", "when", "that", "this", "with", "from", "were", "was",
        "are", "the", "and", "for", "under", "column", "table", "value", "values", "listed",
        "according", "reported", "provide", "show", "does", "did", "row", "rows", "in", "of",
        "to", "as", "by", "on", "a", "an"
    )

    fun answer(query: String, chunks: List<RetrievedChunk>): TableAnswerResult {
        if (!looksLikeTableQuestion(query)) return TableAnswerResult(null, "", TableAnswerConfidence.NONE)
        val rows = chunks.flatMap { parseRows(it) }
        if (rows.isEmpty()) return TableAnswerResult(null, "", TableAnswerConfidence.NONE)

        val queryTerms = terms(query)
        val ranked = rows
            .map { row -> row to rowScore(row, queryTerms) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<TableRow, Int>> { it.second }.thenByDescending { it.first.chunk.score })

        if (ranked.isEmpty()) return TableAnswerResult(null, factsFor(rows.take(MAX_FACT_ROWS)), TableAnswerConfidence.MEDIUM)

        val selectedRows = ranked.take(MAX_FACT_ROWS).map { it.first }
        val calculation = tryCalculate(query, selectedRows)
        if (calculation != null) {
            return TableAnswerResult(calculation, factsFor(selectedRows), TableAnswerConfidence.HIGH)
        }

        val explicitColumns = requestedColumns(query, selectedRows)
        val best = selectedRows.first()
        val directAnswer = directLookupAnswer(query, best, explicitColumns)
        if (directAnswer != null && ranked.first().second >= 2) {
            return TableAnswerResult(directAnswer, factsFor(selectedRows), TableAnswerConfidence.HIGH)
        }

        return TableAnswerResult(null, factsFor(selectedRows), TableAnswerConfidence.MEDIUM)
    }

    private fun looksLikeTableQuestion(query: String): Boolean {
        val q = query.lowercase()
        return listOf(
            "table", "column", " col ", "under ", "row", "value", "amount", "total",
            "sum", "combined", "highest", "largest", "lowest", "ratio", "revenue",
            "ebit", "ebitda", "profit", "assets", "liabilities", "borrowings",
            "inventory", "stock-in-trade", "expenses"
        ).any { q.contains(it) }
    }

    private fun parseRows(chunk: RetrievedChunk): List<TableRow> {
        val headers = mutableListOf<String>()
        val rows = mutableListOf<TableRow>()
        var headerParsed = false

        chunk.content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.startsWith("|") }
            .forEach { line ->
                val cells = parseMarkdownRow(line)
                if (cells.isEmpty()) return@forEach
                if (cells.all { it.replace("-", "").isBlank() }) return@forEach  // separator row
                if (!headerParsed) {
                    headers.clear()
                    headers.addAll(cells)
                    headerParsed = true
                } else {
                    val tableCells = (0 until maxOf(headers.size, cells.size)).mapNotNull { i ->
                        val value = cells.getOrElse(i) { "" }.trim()
                        if (value.isBlank() || value == "-") null
                        else TableCell(label = headers.getOrElse(i) { "Col ${i + 1}" }, value = value)
                    }
                    if (tableCells.isNotEmpty()) rows.add(TableRow(chunk, headers.toList(), tableCells))
                }
            }
        return rows
    }

    private fun parseMarkdownRow(line: String): List<String> =
        line.trim('|').split("|").map { it.trim() }

    private fun terms(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9₹$%.'-]+"))
            .map { it.trim('\'', '.', '-') }
            .filter { it.length > 2 && it !in stopWords }
            .toSet()

    private fun rowScore(row: TableRow, queryTerms: Set<String>): Int {
        val rowTerms = terms(row.searchText)
        var score = queryTerms.count { it in rowTerms }
        row.cells.forEach { cell ->
            val label = normalize(cell.label)
            val value = normalize(cell.value)
            if (label.isNotBlank() && normalize(row.chunk.content).contains(label)) score += 0
            if (queryTerms.any { value.contains(it) || label.contains(it) }) score += 1
        }
        return score
    }

    private fun requestedColumns(query: String, rows: List<TableRow>): List<String> {
        val normalizedQuery = normalize(query)
        val labels = rows.flatMap { row -> row.cells.map { it.label } + row.headers }.distinct()
        val explicit = labels.filter { label ->
            val normalized = normalize(label)
            normalized.length > 1 && normalizedQuery.contains(normalized)
        }
        val colRefs = Regex("\\bcol(?:umn)?\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
            .findAll(query)
            .map { "Col ${it.groupValues[1]}" }
            .toList()
        return (explicit + colRefs).distinctBy { normalize(it) }
    }

    private fun directLookupAnswer(query: String, row: TableRow, explicitColumns: List<String>): String? {
        val selected = if (explicitColumns.isNotEmpty()) {
            row.cells.filter { cell -> explicitColumns.any { sameLabel(it, cell.label) } }
        } else {
            row.cells.take(8)
        }
        if (selected.isEmpty()) return null
        val rowName = row.primaryLabel()
        val values = selected.joinToString("; ") { "${it.label}: ${it.value}" }
        return if (rowName.isBlank()) {
            "$values (Source: ${row.chunk.fileName})"
        } else {
            "$rowName — $values (Source: ${row.chunk.fileName})"
        }
    }

    private fun tryCalculate(query: String, rows: List<TableRow>): String? {
        val q = query.lowercase()
        if (!q.contains("sum") && !q.contains("combined") && !q.contains("total of") &&
            !q.contains("highest") && !q.contains("largest") && !q.contains("lowest")
        ) return null

        val numericCells = rows.flatMap { row ->
            row.cells.mapNotNull { cell ->
                val number = parseNumber(cell.value) ?: return@mapNotNull null
                NumberedCell(row, cell, number)
            }
        }
        if (numericCells.isEmpty()) return null

        if (q.contains("highest") || q.contains("largest") || q.contains("lowest")) {
            val chosen = if (q.contains("lowest")) numericCells.minBy { it.number } else numericCells.maxBy { it.number }
            return "${chosen.row.primaryLabel()} — ${chosen.cell.label}: ${chosen.cell.value} (Source: ${chosen.row.chunk.fileName})"
        }

        val queryTerms = terms(query)
        val selected = numericCells.filter { numbered ->
            val text = normalize("${numbered.row.searchText} ${numbered.cell.label} ${numbered.cell.value}")
            queryTerms.any { text.contains(it) }
        }.distinctBy { "${it.row.chunk.chunkId}:${it.cell.label}:${it.cell.value}" }
        if (selected.size < 2 || selected.size > 8) return null
        val sum = selected.sumOf { it.number }
        val operands = selected.joinToString(" + ") { it.cell.value }
        val formatted = if (abs(sum - sum.toLong()) < 0.0001) sum.toLong().toString() else formatFixed(sum, 2)
        return "$operands = $formatted (Source: ${selected.first().row.chunk.fileName})"
    }

    private fun factsFor(rows: List<TableRow>): String =
        rows.take(MAX_FACT_ROWS).joinToString("\n") { row ->
            val rowName = row.primaryLabel().ifBlank { "Table row" }
            val cells = row.cells.take(10).joinToString(" | ") { "${it.label}: ${it.value}" }
            "- ${row.chunk.fileName}: $rowName | $cells"
        }

    private fun parseNumber(value: String): Double? {
        val cleaned = value
            .replace(",", "")
            .replace("₹", "")
            .replace("$", "")
            .replace("%", "")
            .trim()
        val negative = cleaned.startsWith("(") && cleaned.endsWith(")")
        val number = cleaned.trim('(', ')').toDoubleOrNull() ?: return null
        return if (negative) -number else number
    }

    private fun sameLabel(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        return na == nb || na.contains(nb) || nb.contains(na)
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").replace(Regex("\\s+"), " ").trim()

    private data class TableRow(
        val chunk: RetrievedChunk,
        val headers: List<String>,
        val cells: List<TableCell>
    ) {
        val searchText: String = "${chunk.fileName} ${chunk.content} ${cells.joinToString(" ") { "${it.label} ${it.value}" }}"

        fun primaryLabel(): String = cells.firstOrNull()?.value.orEmpty()
    }

    private data class TableCell(val label: String, val value: String)

    private data class NumberedCell(val row: TableRow, val cell: TableCell, val number: Double)
}
