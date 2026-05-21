@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.anvit.localai.agentic

import com.anvit.localai.data.db.DocumentDao
import com.anvit.localai.document.TokenCounter
import com.anvit.localai.inference.InferenceService
import com.anvit.localai.retrieval.HybridRetriever
import com.anvit.localai.retrieval.RetrievedChunk
import com.anvit.localai.utils.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class AgenticResult(
    val answerFlow: Flow<String>,
    val steps: List<AgentStep>,
    val route: String = QueryRoute.SINGLE_SHOT.name,
    val retrievedChunks: List<RetrievedChunk> = emptyList(),
    val subQueries: List<String> = emptyList()
)

data class BudgetedGenerationInput(
    val prompt: String,
    val systemPrompt: String,
    val chunks: List<RetrievedChunk>
)

class AgenticRagOrchestrator(
    private val inferenceService: InferenceService,
    private val hybridRetriever: HybridRetriever,
    private val documentDao: DocumentDao
) {
    companion object {
        private const val TAG = "AgenticOrchestrator"
        private const val MAX_CRITIQUE_ITERATIONS = 1
        private const val MAX_REQUERY_ATTEMPTS = 2
        private const val DEFAULT_CONTEXT_WINDOW = 4000
        private const val PROMPT_SAFETY_MARGIN_TOKENS = 128
        private const val MIN_OUTPUT_RESERVE_TOKENS = 100
    }

    private val router       = QueryRouter(inferenceService)
    private val decomposer   = QueryDecomposer(inferenceService)
    private val evaluator    = RelevanceEvaluator(inferenceService)
    private val contentReducer = SelectiveContentReducer
    private val critiqueLoop = SelfCritiqueLoop(inferenceService)

    suspend fun process(
        userQuery: String,
        conversationHistory: String,
        enableAgenticRag: Boolean,
        maxChunks: Int,
        enableSelfCritique: Boolean,
        useAgentTools: Boolean,
        collectionId: String? = null,
        imagePath: String? = null,
        audioBytes: ByteArray? = null,
        onStep: suspend (AgentStep) -> Unit = {},
        onSources: suspend (List<RetrievedChunk>) -> Unit = {}
    ): AgenticResult = processInternal(
        userQuery = userQuery,
        conversationHistory = conversationHistory,
        enableAgenticRag = enableAgenticRag,
        maxChunks = maxChunks,
        enableSelfCritique = enableSelfCritique,
        useAgentTools = useAgentTools,
        collectionId = collectionId,
        imagePath = imagePath,
        audioBytes = audioBytes,
        onStep = onStep,
        onSources = onSources,
        traceRecorder = null
    )

    suspend fun processForEval(
        queryId: String,
        userQuery: String,
        conversationHistory: String = "",
        enableAgenticRag: Boolean = true,
        maxChunks: Int = 5,
        enableSelfCritique: Boolean = true,
        useAgentTools: Boolean = false,
        collectionId: String? = null
    ): PipelineTrace {
        val recorder = PipelineTraceRecorder(queryId, userQuery)
        val started = currentTimeMillis()
        val result = processInternal(
            userQuery = userQuery,
            conversationHistory = conversationHistory,
            enableAgenticRag = enableAgenticRag,
            maxChunks = maxChunks,
            enableSelfCritique = enableSelfCritique,
            useAgentTools = useAgentTools,
            collectionId = collectionId,
            traceRecorder = recorder
        )
        val answer = StringBuilder()
        val generationStarted = currentTimeMillis()
        result.answerFlow.collect { answer.append(it) }
        recorder.latencyMs["generation"] = currentTimeMillis() - generationStarted
        recorder.generatedAnswer = answer.toString()
        recorder.totalLatencyMs = currentTimeMillis() - started
        return recorder.snapshot()
    }

    private suspend fun processInternal(
        userQuery: String,
        conversationHistory: String,
        enableAgenticRag: Boolean,
        maxChunks: Int,
        enableSelfCritique: Boolean,
        useAgentTools: Boolean,
        collectionId: String? = null,
        imagePath: String? = null,
        audioBytes: ByteArray? = null,
        onStep: suspend (AgentStep) -> Unit = {},
        onSources: suspend (List<RetrievedChunk>) -> Unit = {},
        traceRecorder: PipelineTraceRecorder? = null
    ): AgenticResult {
        val steps = mutableListOf<AgentStep>()

        val emitStep: suspend (String, String) -> Unit = { type, description ->
            val step = AgentStep(type, description)
            steps.add(step)
            onStep(step)
        }

        val totalChunks = if (collectionId.isNullOrEmpty() || collectionId == "all")
            documentDao.getTotalChunkCount()
        else
            documentDao.getChunkCountForCollection(collectionId)

        val hasDocuments = totalChunks > 0

        // Scope agent tool searches to the active collection before any generation call
        inferenceService.setActiveCollection(collectionId)

        // When agenticRag is disabled or the collection has no documents, fall back to
        // SINGLE_SHOT — it retrieves 0 chunks and generates with an empty context, but
        // still runs through the unified system-prompt path rather than bypassing RAG.
        val route = if (enableAgenticRag && hasDocuments) {
            val routeStarted = currentTimeMillis()
            router.route(userQuery, hasDocuments, conversationHistory).also {
                traceRecorder?.latencyMs?.set("route", currentTimeMillis() - routeStarted)
                emitStep("route", when (it) {
                    QueryRoute.AGENTIC     -> "This looks like a complex question — I'll search in multiple steps"
                    QueryRoute.SINGLE_SHOT -> "Searching your documents for a direct answer"
                    QueryRoute.DIRECT      -> "Answering directly..."
                })
                println("[$TAG] Route: $it")
            }
        } else {
            QueryRoute.SINGLE_SHOT
        }
        traceRecorder?.route = route.name

        return when (route) {
            QueryRoute.SINGLE_SHOT -> {
                emitStep("retrieve", "Searching your documents...")
                val retrieveStarted = currentTimeMillis()
                val chunks  = hybridRetriever.retrieve(userQuery, maxChunks, collectionId)
                traceRecorder?.latencyMs?.set("retrieve", currentTimeMillis() - retrieveStarted)
                traceRecorder?.retrievedChunks?.addAll(chunks)
                traceRecorder?.dedupedChunks?.addAll(chunks.distinctBy { it.chunkId })
                traceRecorder?.finalChunks?.addAll(chunks)
                val reduceStarted = currentTimeMillis()
                val reduced = contentReducer.reduce(userQuery, chunks)
                traceRecorder?.latencyMs?.set("reduce", currentTimeMillis() - reduceStarted)
                traceRecorder?.relevanceAction = RelevanceAction.USE.name
                traceRecorder?.reducedChunks?.addAll(reduced)
                if (traceRecorder?.finalChunks?.isEmpty() == true) {
                    traceRecorder.finalChunks.addAll(reduced)
                }
                val tableResult = TableAnswerEngine.answer(userQuery, reduced)
                val generationChunks = withTableFacts(userQuery, reduced, tableResult)
                val budgeted = buildBudgetedInput(conversationHistory, userQuery, generationChunks)
                traceRecorder?.finalChunks?.clear()
                traceRecorder?.finalChunks?.addAll(budgeted.chunks)
                onSources(budgeted.chunks)
                AgenticResult(
                    answerFlow = inferenceService.generateStream(
                        prompt        = budgeted.prompt,
                        systemPrompt  = budgeted.systemPrompt,
                        useAgentTools = useAgentTools,
                        imagePath     = imagePath,
                        audioBytes    = audioBytes
                    ),
                    steps = steps,
                    route = route.name,
                    retrievedChunks = budgeted.chunks
                )
            }

            QueryRoute.DIRECT -> directFlow(
                userQuery, conversationHistory, imagePath, audioBytes, steps
            )

            QueryRoute.AGENTIC -> agenticFlow(
                userQuery, conversationHistory, steps, emitStep, onSources, maxChunks,
                enableSelfCritique, useAgentTools, collectionId, imagePath, audioBytes,
                traceRecorder
            )
        }
    }

    private suspend fun agenticFlow(
        userQuery: String,
        conversationHistory: String,
        steps: MutableList<AgentStep>,
        emitStep: suspend (String, String) -> Unit,
        onSources: suspend (List<RetrievedChunk>) -> Unit,
        maxChunks: Int,
        enableSelfCritique: Boolean,
        useAgentTools: Boolean,
        collectionId: String?,
        imagePath: String? = null,
        audioBytes: ByteArray? = null,
        traceRecorder: PipelineTraceRecorder? = null
    ): AgenticResult {
        emitStep("decompose", "Breaking your question into focused search queries")
        val decomposeStarted = currentTimeMillis()
        val subQueries = decomposer.decompose(userQuery)
        traceRecorder?.latencyMs?.set("decompose", currentTimeMillis() - decomposeStarted)
        traceRecorder?.subQueries?.addAll(subQueries)

        val allChunks = mutableListOf<RetrievedChunk>()
        val retrieveStarted = currentTimeMillis()
        subQueries.forEach { subQuery ->
            emitStep("retrieve", "Searching for: \"${subQuery.take(60)}\"")
            allChunks.addAll(hybridRetriever.retrieve(subQuery, maxChunks, collectionId))
        }
        traceRecorder?.latencyMs?.set("retrieve", currentTimeMillis() - retrieveStarted)
        traceRecorder?.retrievedChunks?.addAll(allChunks)

        var finalChunks = allChunks.distinctBy { it.chunkId }
            .sortedByDescending { it.score }.take(maxChunks)
        traceRecorder?.dedupedChunks?.addAll(finalChunks)

        var requeryAttempts = 0
        val relevanceStarted = currentTimeMillis()
        var relevanceAction = evaluator.evaluate(userQuery, finalChunks)
        traceRecorder?.latencyMs?.set("relevance", currentTimeMillis() - relevanceStarted)
        traceRecorder?.relevanceAction = relevanceAction.name

        val requeryStarted = currentTimeMillis()
        while (relevanceAction == RelevanceAction.REQUERY && requeryAttempts < MAX_REQUERY_ATTEMPTS) {
            requeryAttempts++
            emitStep("requery", "Results weren't quite right — trying a different search (attempt $requeryAttempts)")
            val rephrased = rephrase(userQuery)
            val newChunks = hybridRetriever.retrieve(rephrased, maxChunks, collectionId)
            if (newChunks.isNotEmpty()) {
                finalChunks    = newChunks
                relevanceAction = evaluator.evaluate(userQuery, finalChunks)
                traceRecorder?.retrievedChunks?.addAll(newChunks)
                traceRecorder?.relevanceAction = relevanceAction.name
            } else break
        }
        if (requeryAttempts > 0) {
            traceRecorder?.latencyMs?.set("requery", currentTimeMillis() - requeryStarted)
        }
        traceRecorder?.requeryAttempts = requeryAttempts

        if (relevanceAction == RelevanceAction.SUPPLEMENT) {
            emitStep("supplement", "Gathering a few more relevant passages to fill any gaps")
            val supplementStarted = currentTimeMillis()
            val extra = hybridRetriever.retrieve(userQuery, 3, collectionId)
            traceRecorder?.latencyMs?.set("supplement", currentTimeMillis() - supplementStarted)
            traceRecorder?.supplementChunks?.addAll(extra)
            finalChunks = (finalChunks + extra).distinctBy { it.chunkId }
                .sortedByDescending { it.score }.take(maxChunks)
        }
        traceRecorder?.finalChunks?.clear()
        traceRecorder?.finalChunks?.addAll(finalChunks)

        emitStep("reduce", "Selecting the most useful passages from what was found")
        val reduceStarted = currentTimeMillis()
        val reducedChunks  = contentReducer.reduce(userQuery, finalChunks)
        traceRecorder?.latencyMs?.set("reduce", currentTimeMillis() - reduceStarted)
        traceRecorder?.reducedChunks?.addAll(reducedChunks)
        traceRecorder?.finalChunks?.clear()
        traceRecorder?.finalChunks?.addAll(reducedChunks)
        val tableResult = TableAnswerEngine.answer(userQuery, reducedChunks)
        var allUsedChunks = withTableFacts(userQuery, reducedChunks, tableResult)
        val budgeted = buildBudgetedInput(conversationHistory, userQuery, allUsedChunks)
        allUsedChunks = budgeted.chunks
        traceRecorder?.finalChunks?.clear()
        traceRecorder?.finalChunks?.addAll(allUsedChunks)
        onSources(allUsedChunks)
        val systemPrompt   = budgeted.systemPrompt

        emitStep("generate", "Writing your answer using ${reducedChunks.size} relevant passage${if (reducedChunks.size == 1) "" else "s"}")

        val responseFlow = flow<String> {
            val fullResponse = StringBuilder()
            inferenceService.generateStream(
                prompt        = budgeted.prompt,
                systemPrompt  = systemPrompt,
                useAgentTools = useAgentTools,
                imagePath     = imagePath,
                audioBytes    = audioBytes
            ).collect { chunk ->
                fullResponse.append(chunk)
                emit(chunk)
            }

            if (enableSelfCritique && reducedChunks.isNotEmpty()) {
                val contextSummary = allUsedChunks.take(5).joinToString("\n---\n") { chunk ->
                    "[${chunk.fileName}]\n${chunk.content.take(700)}"
                }
                val gapQuery = critiqueLoop.evaluate(userQuery, fullResponse.toString(), contextSummary)
                if (gapQuery != null) {
                    traceRecorder?.selfCritiqueTriggered = true
                    traceRecorder?.gapQuery = gapQuery
                    println("[$TAG] Self-critique gap found. Re-querying: $gapQuery")
                    emit("\n\n---\n*Refining with additional context...*\n\n")
                    val extraChunks = hybridRetriever.retrieve(gapQuery, 3, collectionId)
                    traceRecorder?.gapChunks?.addAll(extraChunks)
                    if (extraChunks.isNotEmpty()) {
                        val extraReduced = contentReducer.reduce(gapQuery, extraChunks)
                        allUsedChunks = (allUsedChunks + extraReduced).distinctBy { it.chunkId }
                        onSources(allUsedChunks)
                        traceRecorder?.finalChunks?.clear()
                        traceRecorder?.finalChunks?.addAll(allUsedChunks)
                        val refinedBudgeted = buildBudgetedInput(
                            conversationHistory,
                            userQuery,
                            allUsedChunks,
                            "Previous answer was incomplete. Use the additional context to complete the answer."
                        )
                        allUsedChunks = refinedBudgeted.chunks
                        traceRecorder?.finalChunks?.clear()
                        traceRecorder?.finalChunks?.addAll(allUsedChunks)
                        inferenceService.generateStream(refinedBudgeted.prompt, refinedBudgeted.systemPrompt, false).collect { emit(it) }
                    }
                }
            }
        }

        return AgenticResult(
            answerFlow = responseFlow,
            steps = steps,
            route = QueryRoute.AGENTIC.name,
            retrievedChunks = allUsedChunks,
            subQueries = subQueries
        )
    }

    private fun directFlow(
        userQuery: String,
        conversationHistory: String,
        imagePath: String?,
        audioBytes: ByteArray?,
        steps: MutableList<AgentStep>
    ): AgenticResult {
        // buildBudgetedInput with no chunks produces buildSystemPrompt("") — the no-context
        // variant that answers from general knowledge and budgets history into the context window.
        // For context-reuse follow-ups the prior RAG response is already in conversationHistory,
        // so the model can answer from it without any new retrieval.
        val budgeted = buildBudgetedInput(conversationHistory, userQuery, emptyList())
        return AgenticResult(
            answerFlow = inferenceService.generateStream(
                prompt        = budgeted.prompt,
                systemPrompt  = budgeted.systemPrompt,
                useAgentTools = false,
                imagePath     = imagePath,
                audioBytes    = audioBytes
            ),
            steps           = steps,
            route           = QueryRoute.DIRECT.name,
            retrievedChunks = emptyList(),
            subQueries      = emptyList()
        )
    }

    private fun formatScore(score: Float): String {
        val s = (kotlin.math.round(score * 100) / 100.0).toString()
        return s.substringBefore('.') + "." + s.substringAfter('.').padEnd(2, '0').take(2)
    }

    private fun buildContextString(chunks: List<RetrievedChunk>): String {
        if (chunks.isEmpty()) return ""
        return chunks.joinToString("\n\n---\n\n") { chunk ->
            "[Source: ${chunk.fileName}, relevance: ${formatScore(chunk.score)}]\n${chunk.content}"
        }
    }

    private fun withTableFacts(
        userQuery: String,
        chunks: List<RetrievedChunk>,
        tableResult: TableAnswerResult = TableAnswerEngine.answer(userQuery, chunks)
    ): List<RetrievedChunk> {
        if (tableResult.confidence == TableAnswerConfidence.NONE || tableResult.facts.isBlank()) return chunks
        val anchor = chunks.firstOrNull() ?: return chunks
        val content = if (tableResult.confidence == TableAnswerConfidence.HIGH && tableResult.answer != null) {
            "VERIFIED ANSWER extracted from table data: ${tableResult.answer}\n\nTABLE FACTS:\n${tableResult.facts}\n\nPresent this answer in clear, natural language. Preserve all numeric values exactly."
        } else {
            "TABLE FACTS extracted from retrieved table rows:\n${tableResult.facts}\n\nUse these facts exactly. Preserve numeric formatting and refuse if a requested value is missing."
        }
        val factChunk = anchor.copy(
            chunkId = "table-facts:${anchor.chunkId}",
            content = content,
            score = Float.MAX_VALUE,
            vectorScore = 0f,
            bm25Rank = 0,
            lexicalScore = 0f,
            retrievalSource = "table_facts"
        )
        return listOf(factChunk) + chunks
    }

    internal fun buildBudgetedInput(
        conversationHistory: String,
        userQuery: String,
        chunks: List<RetrievedChunk>,
        note: String? = null
    ): BudgetedGenerationInput {
        val contextWindow = activeContextWindow()
        val outputReserve = outputReserve(contextWindow)
        val inputLimit = (contextWindow - outputReserve - PROMPT_SAFETY_MARGIN_TOKENS)
            .coerceAtLeast(512)

        fun tokensFor(candidateChunks: List<RetrievedChunk>, history: String): Int {
            val system = buildSystemPrompt(buildContextString(candidateChunks))
            val prompt = buildPrompt(history, userQuery, note)
            return TokenCounter.estimate("$system\n\n$prompt")
        }

        val selected = mutableListOf<RetrievedChunk>()
        val orderedChunks = chunks.sortedByDescending { it.score }
        for (chunk in orderedChunks) {
            val candidate = selected + chunk
            if (tokensFor(candidate, "") <= inputLimit) {
                selected.add(chunk)
                continue
            }

            val remaining = inputLimit - tokensFor(selected, "") - 24
            if (remaining > 80) {
                var trimmedBudget = remaining
                while (trimmedBudget > 80) {
                    val reducedChunk = contentReducer.reduce(
                        userQuery,
                        listOf(chunk),
                        maxStructuredTokensPerChunk = trimmedBudget
                    ).single()
                    val trimmed = reducedChunk.copy(content = trimToTokenBudget(reducedChunk.content, trimmedBudget))
                    if (tokensFor(selected + trimmed, "") <= inputLimit) {
                        selected.add(trimmed)
                        break
                    }
                    trimmedBudget /= 2
                }
            }
            break
        }

        val historyLines = conversationHistory.lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
        val selectedHistory = ArrayDeque<String>()
        for (line in historyLines.asReversed()) {
            selectedHistory.addFirst(line)
            val candidateHistory = selectedHistory.joinToString("\n")
            if (tokensFor(selected, candidateHistory) > inputLimit) {
                selectedHistory.removeAt(0)
                break
            }
        }

        return BudgetedGenerationInput(
            prompt = buildPrompt(selectedHistory.joinToString("\n"), userQuery, note),
            systemPrompt = buildSystemPrompt(buildContextString(selected)),
            chunks = selected
        )
    }

    private fun activeContextWindow(): Int {
        val model = inferenceService.getCurrentModel()
        return if (model != null) inferenceService.getEffectiveMaxTokens(model) else DEFAULT_CONTEXT_WINDOW
    }

    private fun outputReserve(contextWindow: Int): Int {
        val configuredOutput = inferenceService.getMaxOutputTokens().coerceAtLeast(1)
        val maxPracticalReserve = (contextWindow / 2).coerceAtLeast(MIN_OUTPUT_RESERVE_TOKENS)
        return configuredOutput.coerceAtMost(maxPracticalReserve)
    }

    private fun trimToTokenBudget(text: String, tokenBudget: Int): String {
        val wordBudget = (tokenBudget / 1.3).toInt().coerceAtLeast(1)
        return text.split(Regex("\\s+")).take(wordBudget).joinToString(" ")
    }

    private fun buildSystemPrompt(context: String): String {
        val now = Instant.fromEpochMilliseconds(currentTimeMillis())
        val tz = TimeZone.currentSystemDefault()
        val localTime = now.toLocalDateTime(tz)
        val dayNames = listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")
        val dayName = dayNames.getOrElse(localTime.dayOfWeek.ordinal) { "" }
        val hour12 = when {
            localTime.hour == 0  -> 12
            localTime.hour <= 12 -> localTime.hour
            else                 -> localTime.hour - 12
        }
        val amPm = if (localTime.hour < 12) "AM" else "PM"
        val timeString = "$dayName, ${localTime.dayOfMonth} ${monthName(localTime.monthNumber)} ${localTime.year}, $hour12:${localTime.minute.toString().padStart(2,'0')} $amPm"
        val tzId = tz.id

        val locationNote = """
Timezone (approximate region hint only): $tzId
IMPORTANT: Do NOT assert the user's exact city or location with confidence — the timezone only hints at a broad region. If your answer depends on a precise location (e.g. nearby restaurants, local laws, specific addresses), ask the user to share their exact location instead of guessing.""".trimIndent()

        return if (context.isBlank()) {
            """
You are Anvit, a helpful and accurate AI assistant.
Current date and time: $timeString
$locationNote

Answer the question directly and concisely. When asked about the current time or date, use the information provided above.
If the user asks about document content, financial facts, tables, entities, or source material and no document context is provided, say: "I don't have enough information in the selected documents to answer this."
            """.trimIndent()
        } else {
            """
You are Anvit, a helpful and accurate AI document assistant.
Current date and time: $timeString
$locationNote

Answer the user's question directly and specifically — do not pad with filler, caveats, or repetition.
Be crisp: give the exact answer first, then supporting detail only if it adds value.
Always cite the source document name when referencing specific content.

	IMPORTANT — Grounding and refusal:
	- Use only the provided document context for document-specific claims.
	- You may synthesize across passages, but every factual claim must be supported by the context.
	- If the context does not contain enough relevant evidence to answer the question, say: "I don't have enough information in the selected documents to answer this."
	- Do not use outside knowledge to fill missing financial, table, entity, or document facts.
	- For table or financial answers, copy numbers, parentheses, currency symbols, percent signs, row labels, and column labels exactly as shown.
	- If a question asks for multiple values, answer each requested value separately. Do not skip fields silently.
	- Do not calculate sums, differences, or rankings unless all operands are explicitly present in the context.
	
	DOCUMENT CONTEXT:
	$context
            """.trimIndent()
        }
    }

    private fun monthName(month: Int) = listOf(
        "Jan","Feb","Mar","Apr","May","Jun",
        "Jul","Aug","Sep","Oct","Nov","Dec"
    ).getOrElse(month - 1) { "$month" }

    private fun buildPrompt(history: String, query: String, note: String?): String {
        val noteStr = if (note != null) "\n[Note: $note]" else ""
        return if (history.isBlank()) "$query$noteStr"
        else "$history\n\nuser: $query$noteStr"
    }

    private suspend fun rephrase(query: String): String {
        return try {
            inferenceService.generateResponse(
                prompt = "Rephrase this search query using different keywords:\n\"$query\"\n\nRephrased:",
                systemPrompt = "You rephrase search queries. Output ONLY the rephrased query, nothing else.",
                allowThinking = false
            ).trim().removeSurrounding("\"")
        } catch (e: Exception) { query }
    }
}
