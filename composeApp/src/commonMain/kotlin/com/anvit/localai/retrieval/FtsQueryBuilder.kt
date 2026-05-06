package com.anvit.localai.retrieval

/**
 * Builds broad FTS4 MATCH expressions from natural-language document questions.
 *
 * FTS4 treats whitespace as AND, which makes long questions brittle. Using OR
 * with prefix terms keeps exact financial/table keywords in play without
 * requiring every filler word to match the same chunk.
 */
object FtsQueryBuilder {
    private val tokenRegex = Regex("[A-Za-z0-9]+(?:[-&][A-Za-z0-9]+)*")
    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "by", "can", "did", "do", "does",
        "for", "from", "had", "has", "have", "how", "i", "in", "is", "it", "its",
        "me", "of", "on", "or", "should", "show", "than", "that", "the", "their",
        "these", "this", "to", "was", "were", "what", "when", "where", "which",
        "who", "with", "would", "you", "your"
    )

    fun build(query: String, maxTerms: Int = 12): String {
        val terms = terms(query, maxTerms).filterNot { '-' in it || '&' in it }
        return terms.joinToString(" OR ") { "${escapeMatchTerm(it)}*" }
    }

    fun terms(query: String, maxTerms: Int = 12): List<String> {
        val rawTerms = tokenRegex.findAll(query)
            .flatMap { match ->
                val token = match.value.trim().lowercase()
                if ('-' in token || '&' in token) {
                    sequenceOf(token) + token.split('-', '&').asSequence()
                } else {
                    sequenceOf(token)
                }
            }
            .map { it.trim() }
            .filter { token ->
                token.length >= 2 &&
                    token !in stopWords &&
                    token.any { it.isLetterOrDigit() }
            }
            .distinct()
            .toList()

        return prioritize(rawTerms).take(maxTerms)
    }

    private fun prioritize(tokens: List<String>): List<String> =
        tokens.sortedWith(
            compareByDescending<String> { token ->
                token.any { it.isDigit() } ||
                    token.all { !it.isLetter() || it.isUpperCase() } ||
                    token in financialTerms
            }.thenByDescending { it.length }
        )

    private fun escapeMatchTerm(term: String): String =
        term.replace("\"", "\"\"")
            .replace("*", "")
            .replace("'", "")
            .trim()

    private val financialTerms = setOf(
        "ebitda", "capex", "revenue", "profit", "margin", "assets", "liabilities",
        "borrowings", "inventory", "stock", "trade", "o2c", "jio", "retail",
        "quarter", "cash", "flow", "expense", "income"
    )
}
