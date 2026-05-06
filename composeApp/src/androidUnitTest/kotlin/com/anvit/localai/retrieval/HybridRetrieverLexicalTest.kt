package com.anvit.localai.retrieval

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anvit.localai.data.db.AnvitDatabase
import com.anvit.localai.data.db.entities.ChunkEntity
import com.anvit.localai.data.db.entities.DocumentEntity
import com.anvit.localai.embedding.EmbeddingService
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HybridRetrieverLexicalTest {
    @Test
    fun lexicalRetrievalUsesBroadFtsAndReturnsRankedChunks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnvitDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val dao = db.documentDao()
            dao.insertDocument(DocumentEntity(
                id = "doc",
                fileName = "financial.pdf",
                filePath = "",
                pageCount = 1,
                chunkCount = 2,
                status = "READY"
            ))
            dao.insertChunks(listOf(
                ChunkEntity(
                    id = "c1",
                    docId = "doc",
                    fileName = "financial.pdf",
                    chunkIndex = 0,
                    content = "Table: Segment, Revenue, EBITDA\nSegment: O2C | Revenue: 100 | EBITDA: 20 | Operating margin: 20%",
                    embedding = null
                ),
                ChunkEntity(
                    id = "c2",
                    docId = "doc",
                    fileName = "financial.pdf",
                    chunkIndex = 1,
                    content = "Registered Office: Maker Chambers",
                    embedding = null
                )
            ))
            dao.rebuildChunksFts()

            val retriever = HybridRetriever(dao, NoopEmbeddingService)
            val results = retriever.retrieveLexical("What was the Operating margin for O2C?", maxResults = 5)

            assertTrue(results.isNotEmpty())
            assertEquals("c1", results.first().chunkId)
            assertTrue(results.first().bm25Rank > 0)
            assertTrue(results.first().lexicalScore > 0f)
            assertEquals("lexical", results.first().retrievalSource)
        } finally {
            db.close()
        }
    }

    @Test
    fun groupExpansionIsBoundedAndIncludesGroupHead() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnvitDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val dao = db.documentDao()
            val groupId = "table-group"
            dao.insertDocument(DocumentEntity(
                id = "doc",
                fileName = "financial.pdf",
                filePath = "",
                pageCount = 1,
                chunkCount = 10,
                status = "READY"
            ))
            val chunks = buildList {
                add(ChunkEntity(
                    id = "head",
                    docId = "doc",
                    fileName = "financial.pdf",
                    chunkIndex = 0,
                    content = "Table summary: 9 rows. Headers: Segment, Revenue, EBITDA",
                    embedding = null,
                    groupId = groupId,
                    isGroupHead = true
                ))
                for (index in 1..9) {
                    val segment = if (index == 5) "O2C" else "Other $index"
                    add(ChunkEntity(
                        id = "row$index",
                        docId = "doc",
                        fileName = "financial.pdf",
                        chunkIndex = index,
                        content = "Table (continued) — Headers: Segment, Revenue, EBITDA\nSegment: $segment | Revenue: ${index * 100} | EBITDA: ${index * 10}",
                        embedding = null,
                        groupId = groupId
                    ))
                }
            }
            dao.insertChunks(chunks)
            dao.rebuildChunksFts()

            val retriever = HybridRetriever(dao, NoopEmbeddingService)
            val results = retriever.retrieve("O2C EBITDA", maxResults = 1)

            assertTrue(results.size <= 4)
            assertTrue(results.any { it.chunkId == "row5" })
            assertTrue(results.any { it.chunkId == "head" })
            assertTrue(results.none { it.chunkId == "row9" })
        } finally {
            db.close()
        }
    }

    private object NoopEmbeddingService : EmbeddingService {
        override suspend fun generateEmbedding(text: String): FloatArray? = null
        override suspend fun initialize(): Boolean = true
        override fun isInitialized(): Boolean = true
        override fun cleanup() = Unit
        override fun getModelName(): String = "noop"
    }
}
