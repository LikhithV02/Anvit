package com.anvit.localai.agentic

import com.anvit.localai.inference.InferenceService

enum class QueryRoute { SINGLE_SHOT, AGENTIC, DIRECT }

class QueryRouter(private val inferenceService: InferenceService) {
    companion object {
        private val SYSTEM_PROMPT = """
You are a query classifier for a document-search system. The user has a document collection loaded.
Classify each query into exactly one category:

DIRECT - The query can be answered without any new document retrieval. Use for:
  - Mathematical calculations (e.g. "what is 15% of 500")
  - General world knowledge (e.g. "what is the capital of France", "who wrote Hamlet")
  - Greetings and chitchat (e.g. "hello", "thanks", "how are you")
  - Questions about this conversation or the AI itself (e.g. "what can you do", "explain that again")
  - Speculative, unanswerable, or clearly outside-the-documents questions
  - Follow-up questions where the recent conversation already contains the retrieved document context needed to answer (e.g. "what was that number again?", "tell me more about the first point", "you mentioned X earlier")

SINGLE_SHOT - Simple, focused lookups that require one retrieval pass over the loaded documents.

AGENTIC - Questions requiring multiple retrievals, comparison across documents, analysis, synthesis, trend/difference reasoning, or combining values from multiple tables.

Reply with ONLY ONE WORD: DIRECT, SINGLE_SHOT, or AGENTIC.
        """.trimIndent()
    }

    suspend fun route(query: String, hasDocuments: Boolean, conversationHistory: String = ""): QueryRoute {
        if (!hasDocuments) return QueryRoute.SINGLE_SHOT
        heuristicRoute(query, conversationHistory)?.let {
            println("[QueryRouter] Heuristic route: $it")
            return it
        }
        return try {
            val historySnippet = if (conversationHistory.isBlank()) ""
                else "\n\nRecent conversation:\n${conversationHistory.takeLast(400)}"
            val response = inferenceService.generateResponse(
                prompt = "Query: \"$query\"$historySnippet\n\nClassify this query:",
                systemPrompt = SYSTEM_PROMPT,
                allowThinking = false
            ).trim().uppercase()
            when {
                response.contains("DIRECT")  -> QueryRoute.DIRECT
                response.contains("AGENTIC") -> QueryRoute.AGENTIC
                else                          -> QueryRoute.SINGLE_SHOT
            }.also { println("[QueryRouter] Route: $it") }
        } catch (e: Exception) {
            println("[QueryRouter] Routing failed, defaulting to SINGLE_SHOT: ${e.message}")
            QueryRoute.SINGLE_SHOT
        }
    }

    private fun heuristicRoute(query: String, conversationHistory: String = ""): QueryRoute? {
        val q = query.lowercase()
        if (looksUnanswerableOrSpeculative(q)) return QueryRoute.DIRECT
        if (looksLikeDirectAnswer(q)) return QueryRoute.DIRECT
        if (looksLikeFollowUpOnRetrievedContext(q, conversationHistory)) return QueryRoute.DIRECT
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

    private fun looksLikeDirectAnswer(query: String): Boolean {
        val greetings = listOf(
            "hello", "hi ", "hey ", "good morning", "good afternoon", "good evening",
            "thanks", "thank you", "bye", "goodbye"
        )
        if (greetings.any { query.startsWith(it) || query.trimEnd() == it.trim() }) return true

        val conversationMeta = listOf(
            "explain that again", "rephrase that", "say that again", "what did you mean",
            "summarize our conversation", "summarize what we discussed",
            "what can you do", "what are your capabilities", "who are you", "what are you"
        )
        if (conversationMeta.any { query.contains(it) }) return true

        val mathPattern = Regex("""^\s*[\d\s+\-*/%(). ]+\s*$""")
        if (mathPattern.matches(query)) return true
        val mathPhraseSignals = listOf(
            "% of ", "percent of ", "percentage of ",
            "divided by ", "multiplied by ", "times ", "square root", "power of"
        )
        if (query.any { it.isDigit() } && mathPhraseSignals.any { query.contains(it) }) return true

        val worldKnowledge = listOf(
            "capital of ", "president of ", "prime minister of ",
            "population of ", "currency of ",
            "who wrote ", "who invented ", "who discovered ",
            "define ", "meaning of "
        )
        if (worldKnowledge.any { query.contains(it) }) return true

        return false
    }

    private fun looksLikeFollowUpOnRetrievedContext(query: String, history: String): Boolean {
        if (history.isBlank()) return false
        val hasPriorRetrievedContext = history.contains("[Source:")
        if (!hasPriorRetrievedContext) return false
        val followUpSignals = listOf(
            "you mentioned", "as you said", "from what you said", "you said",
            "that figure", "that number", "that value", "that amount",
            "that point", "the first point", "the second point", "the last point",
            "tell me more about that", "elaborate on that", "expand on that",
            "what was that", "what were those", "going back to",
            "based on what we discussed", "from the previous answer",
            "in your previous response", "in your last response"
        )
        return followUpSignals.any { query.contains(it) }
    }
}
