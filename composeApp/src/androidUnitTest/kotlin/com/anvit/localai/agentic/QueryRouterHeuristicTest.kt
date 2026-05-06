package com.anvit.localai.agentic

import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.inference.InferenceService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryRouterHeuristicTest {
    @Test
    fun directLookupsStaySingleShotEvenWhenClassifierWouldOverroute() = runBlocking {
        val router = QueryRouter(alwaysAgenticInference)

        assertEquals(
            QueryRoute.SINGLE_SHOT,
            router.route(
                "According to the annual performance report, what was the year-over-year increase in O2C revenue and what primarily drove this increase?",
                hasDocuments = true
            )
        )
    }

    @Test
    fun tableAndColumnLookupsUseAgenticRoute() = runBlocking {
        val router = QueryRouter(alwaysSingleShotInference)

        assertEquals(
            QueryRoute.AGENTIC,
            router.route(
                "According to the Segment Results (EBIT) table, what was the EBIT for Digital Services in Col 6?",
                hasDocuments = true
            )
        )
    }

    @Test
    fun speculativeAdversarialQuestionsStaySingleShot() = runBlocking {
        val router = QueryRouter(alwaysAgenticInference)

        assertEquals(
            QueryRoute.SINGLE_SHOT,
            router.route(
                "What is the optimal synergy multiplier that will quantifiably enhance future-proofed operational agility?",
                hasDocuments = true
            )
        )
    }

    @Test
    fun explicitComparisonsUseAgenticRoute() = runBlocking {
        val router = QueryRouter(alwaysSingleShotInference)

        assertEquals(
            QueryRoute.AGENTIC,
            router.route(
                "Compare Jio revenue growth versus Retail revenue growth across documents.",
                hasDocuments = true
            )
        )
    }

    @Test
    fun syntheticMultiHopDocumentQuestionsUseAgenticRoute() = runBlocking {
        val router = QueryRouter(alwaysSingleShotInference)

        assertEquals(
            QueryRoute.AGENTIC,
            router.route(
                "According to the provided documents, what was RIL's Other Income and what factors impacted segment profitability?",
                hasDocuments = true
            )
        )
    }

    private val alwaysAgenticInference = FakeInference("AGENTIC")
    private val alwaysSingleShotInference = FakeInference("SINGLE_SHOT")

    private class FakeInference(private val response: String) : InferenceService {
        override suspend fun generateResponse(
            prompt: String,
            systemPrompt: String?,
            imagePath: String?,
            audioBytes: ByteArray?
        ): String = response

        override fun generateStream(
            prompt: String,
            systemPrompt: String,
            useAgentTools: Boolean,
            imagePath: String?,
            audioBytes: ByteArray?
        ): Flow<String> = emptyFlow()

        override fun setGenerationParams(
            topK: Int,
            topP: Float,
            temperature: Float,
            enableThinking: Boolean,
            maxTokens: Int,
            contextWindow: Int?,
            accelerator: String
        ) = Unit

        override suspend fun loadModel(model: GemmaModel): Boolean = true
        override suspend fun unloadModel() = Unit
        override fun getCurrentModel(): GemmaModel? = null
        override fun isLoaded(): Boolean = true
        override fun getEffectiveMaxTokens(model: GemmaModel): Int = model.contextWindowSize
        override fun setActiveCollection(collectionId: String?) = Unit
        override fun recordSessionReset(chatId: String) = Unit
        override fun wasSessionRecentlyReset(chatId: String): Boolean = false
        override fun extractCurrentUserMessage(prompt: String): String = prompt
    }
}
