package com.anvit.localai.agentic

import com.anvit.localai.data.db.DocumentDao
import com.anvit.localai.data.db.entities.ChunkEntity
import com.anvit.localai.data.db.entities.DocumentEntity
import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.data.models.GemmaModels
import com.anvit.localai.document.TokenCounter
import com.anvit.localai.inference.InferenceService
import com.anvit.localai.retrieval.HybridRetriever
import com.anvit.localai.retrieval.RetrievedChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgenticPromptBudgetTest {
    @Test
    fun budgetTrimsLongContextBelowActiveLimitAndKeepsCurrentQuery() {
        val orchestrator = AgenticRagOrchestrator(
            inferenceService = FakeInferenceService(contextWindow = 4000, maxOutputTokens = 1000),
            hybridRetriever = HybridRetriever(FakeDocumentDao, FakeEmbeddingService),
            documentDao = FakeDocumentDao
        )
        val longRows = (1..250).joinToString("\n") { index ->
            val segment = if (index == 127) "O2C" else "Other $index"
            "Segment: $segment | Revenue: ${index * 100} | EBITDA: ${index * 10} | Margin: ${index}% | Notes: repeated financial detail"
        }
        val chunks = listOf(chunk("Table: Segment, Revenue, EBITDA, Margin\n$longRows"))
        val history = (1..12).joinToString("\n") { index ->
            if (index % 2 == 0) "assistant: older answer $index ".repeat(400)
            else "user: older question $index ".repeat(400)
        }

        val budgeted = orchestrator.buildBudgetedInput(
            conversationHistory = history,
            userQuery = "What is the O2C EBITDA margin?",
            chunks = chunks
        )
        val totalTokens = TokenCounter.estimate("${budgeted.systemPrompt}\n\n${budgeted.prompt}")

        assertTrue(totalTokens < 4000)
        assertTrue(budgeted.prompt.contains("What is the O2C EBITDA margin?"))
        assertTrue(budgeted.systemPrompt.contains("Table: Segment"))
        assertTrue(budgeted.systemPrompt.contains("O2C"))
        assertFalse(budgeted.prompt.contains("older question 1"))
    }

    private fun chunk(content: String) = RetrievedChunk(
        chunkId = "c1",
        docId = "d1",
        fileName = "financial.pdf",
        chunkIndex = 0,
        content = content,
        score = 1f,
        vectorScore = 1f,
        bm25Rank = 1
    )

    private class FakeInferenceService(
        private val contextWindow: Int,
        private val maxOutputTokens: Int
    ) : InferenceService {
        override suspend fun generateResponse(
            prompt: String,
            systemPrompt: String?,
            allowThinking: Boolean,
            imagePath: String?,
            audioBytes: ByteArray?
        ): String = ""

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

        override fun setActiveCollection(collectionId: String?) = Unit
        override suspend fun loadModel(model: GemmaModel): Boolean = true
        override suspend fun unloadModel() = Unit
        override fun getCurrentModel(): GemmaModel = GemmaModels.E2B
        override fun isLoaded(): Boolean = true
        override fun getEffectiveMaxTokens(model: GemmaModel): Int = contextWindow
        override fun getMaxOutputTokens(): Int = maxOutputTokens
        override fun recordSessionReset(chatId: String) = Unit
        override fun wasSessionRecentlyReset(chatId: String): Boolean = false
        override fun extractCurrentUserMessage(prompt: String): String = prompt
    }

    private object FakeEmbeddingService : com.anvit.localai.embedding.EmbeddingService {
        override suspend fun generateEmbedding(text: String): FloatArray? = null
        override suspend fun initialize(): Boolean = true
        override fun isInitialized(): Boolean = true
        override fun cleanup() = Unit
        override fun getModelName(): String = "fake"
    }

    private object FakeDocumentDao : DocumentDao {
        override fun getDocumentsForCollection(collectionId: String): Flow<List<DocumentEntity>> = flowOf(emptyList())
        override fun getAllDocuments(): Flow<List<DocumentEntity>> = flowOf(emptyList())
        override suspend fun getDocumentById(id: String): DocumentEntity? = null
        override suspend fun insertDocument(doc: DocumentEntity) = Unit
        override suspend fun updateDocument(doc: DocumentEntity) = Unit
        override suspend fun deleteDocument(doc: DocumentEntity) = Unit
        override suspend fun deleteDocumentById(id: String) = Unit
        override suspend fun deleteDocumentsForCollection(collectionId: String) = Unit
        override suspend fun insertChunk(chunk: ChunkEntity) = Unit
        override suspend fun insertChunks(chunks: List<ChunkEntity>) = Unit
        override suspend fun rebuildChunksFts() = Unit
        override suspend fun getChunksForDocument(docId: String): List<ChunkEntity> = emptyList()
        override suspend fun getAllChunks(): List<ChunkEntity> = emptyList()
        override suspend fun getChunksForCollection(collectionId: String): List<ChunkEntity> = emptyList()
        override suspend fun deleteChunksForDocument(docId: String) = Unit
        override suspend fun getTotalChunkCount(): Int = 0
        override suspend fun getChunkCountForCollection(collectionId: String): Int = 0
        override suspend fun getChunksForGroup(groupId: String): List<ChunkEntity> = emptyList()
        override suspend fun searchChunksFts(query: String, limit: Int): List<ChunkEntity> = emptyList()
        override suspend fun searchChunksFtsForCollection(query: String, collectionId: String, limit: Int): List<ChunkEntity> = emptyList()
    }
}
