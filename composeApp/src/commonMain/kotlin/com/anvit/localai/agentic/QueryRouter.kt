package com.anvit.localai.agentic

import com.anvit.localai.inference.InferenceService

enum class QueryRoute { SINGLE_SHOT, AGENTIC }

class QueryRouter(private val inferenceService: InferenceService) {
    companion object {
        private val SYSTEM_PROMPT = """
You are a query classifier for a document-search system. The user has already chosen a document collection, so every query should be answered using that collection.
Classify queries into exactly one category:
SINGLE_SHOT - Simple direct lookups that need one focused retrieval, conversational/date questions, or broad questions that are probably outside the selected documents.
AGENTIC - Questions requiring multiple retrievals, comparison, analysis, synthesis, cross-document evidence, multiple entities, multiple metrics, trend/difference reasoning, or table values that must be combined.
Reply with ONLY ONE WORD: SINGLE_SHOT or AGENTIC.
        """.trimIndent()
    }

    suspend fun route(query: String, hasDocuments: Boolean): QueryRoute {
        if (!hasDocuments) return QueryRoute.SINGLE_SHOT
        heuristicRoute(query)?.let {
            println("[QueryRouter] Heuristic route: $it")
            return it
        }
        return try {
            val response = inferenceService.generateResponse(
                prompt = "Query: \"$query\"\n\nClassify this query:",
                systemPrompt = SYSTEM_PROMPT,
                allowThinking = false
            ).trim().uppercase()
            (if (response.contains("AGENTIC")) QueryRoute.AGENTIC else QueryRoute.SINGLE_SHOT)
                .also { println("[QueryRouter] Route: $it") }
        } catch (e: Exception) {
            println("[QueryRouter] Routing failed, defaulting to SINGLE_SHOT: ${e.message}")
            QueryRoute.SINGLE_SHOT
        }
    }

    private fun heuristicRoute(query: String): QueryRoute? {
        val q = query.lowercase()
        if (looksUnanswerableOrSpeculative(q)) return QueryRoute.SINGLE_SHOT
        if (looksLikeTableLookup(q)) return QueryRoute.AGENTIC
        if (looksLikeMultiHopDocumentQuestion(q)) return QueryRoute.AGENTIC
        if (looksLikeDirectLookup(q)) return QueryRoute.SINGLE_SHOT

        val agenticSignals = listOf(
            "compare", "comparison", "versus", " vs ", "difference", "differ",
            "across documents", "all documents", "between documents", "trend", "change from",
            "summarize the key", "synthes", "relationship", "correlat",
            "why did", "what are the risks"
        )
        if (agenticSignals.any { q.contains(it) }) return QueryRoute.AGENTIC

        val conjunctionCount = Regex("\\b(and|versus|vs|while|whereas)\\b").findAll(q).count()
        val numericOrMetricCount = Regex("\\b(q[1-4]|fy\\d{2,4}|revenue|ebitda|profit|margin|capex|cash flow|assets|liabilities|borrowings|segment)\\b")
            .findAll(q)
            .count()
        if (conjunctionCount >= 1 && numericOrMetricCount >= 2) return QueryRoute.AGENTIC

        return null
    }

    private fun looksLikeTableLookup(query: String): Boolean {
        val tableSignals = listOf(
            "according to the table",
            "expenses table",
            "stock-in-trade table",
            "segment results",
            " col ",
            " column ",
            " row ",
            "under col",
            "under column",
            "in column",
            "inventory turnover",
            "payment of lease liabilities",
            "production figures",
            "amount associated with"
        )
        return tableSignals.any { query.contains(it) }
    }

    private fun looksLikeDirectLookup(query: String): Boolean {
        val startsDirectly = listOf(
            "what is ", "what was ", "what were ", "which ", "who ", "when ",
            "where ", "under which ", "according to ", "based on ", "for the ",
            "describe ", "identify "
        ).any { query.startsWith(it) }
        if (!startsDirectly) return false

        val hardMultiDocSignals = listOf(
            "compare", "comparison", "versus", " vs ", "difference between",
            "across different documents", "across all documents", "all documents",
            "synthesize", "summarize across"
        )
        if (hardMultiDocSignals.any { query.contains(it) }) return false

        val conjunctionCount = Regex("\\b(and|including)\\b").findAll(query).count()
        return conjunctionCount <= 3
    }

    private fun looksLikeMultiHopDocumentQuestion(query: String): Boolean {
        val documentSignals = listOf(
            "provided documents",
            "according to the documents",
            "in the documents",
            "first provided chunk",
            "second provided chunk",
            "first chunk",
            "second chunk"
        )
        if (documentSignals.any { query.contains(it) }) return true

        val bridgePatterns = listOf(
            " and what factors ",
            " and which components ",
            " and what operational ",
            " and what amount ",
            " and where ",
            " and how ",
            " and then ",
            " along with ",
            " as well as ",
            " sum of "
        )
        return bridgePatterns.any { query.contains(it) }
    }

    private fun looksUnanswerableOrSpeculative(query: String): Boolean {
        val outsideDocumentSignals = listOf(
            "favorite color", "weather", "stock price today", "current market price",
            "latest news", "tomorrow", "near me"
        )
        val speculativeSignals = listOf(
            "optimal", "unforeseen", "unspoken", "future-proof", "future proof",
            "market sentiment", "ripple effects", "next five years", "anticipated",
            "undefined", "precisely translate", "maximize roi", "potential supply chain"
        )
        return outsideDocumentSignals.any { query.contains(it) } ||
            speculativeSignals.any { query.contains(it) }
    }
}
