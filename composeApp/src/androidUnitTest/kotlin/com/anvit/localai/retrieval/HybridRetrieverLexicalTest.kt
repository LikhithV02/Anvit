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
                    content = "| Segment | Revenue | EBITDA | Operating margin |\n|---|---|---|---|\n| O2C | 100 | 20 | 20% |",
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
                    content = "| Segment | Revenue | EBITDA |\n|---|---|---|",
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
                        content = "| Segment | Revenue | EBITDA |\n|---|---|---|\n| $segment | ${index * 100} | ${index * 10} |",
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

    @Test
    fun ocrChildRetrievalIncludesParentSectionAndNearbySibling() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnvitDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val dao = db.documentDao()
            val sectionId = "sec-financials"
            dao.insertDocument(DocumentEntity(
                id = "doc",
                fileName = "financial.pdf",
                filePath = "",
                pageCount = 1,
                chunkCount = 3,
                status = "READY"
            ))
            dao.insertChunks(listOf(
                ChunkEntity(
                    id = sectionId,
                    docId = "doc",
                    fileName = "financial.pdf",
                    chunkIndex = 0,
                    content = "CONSOLIDATED FINANCIAL HIGHLIGHTS",
                    embedding = null,
                    chunkType = "SECTION",
                    sectionId = sectionId
                ),
                ChunkEntity(
                    id = "child1",
                    docId = "doc",
                    fileName = "financial.pdf",
                    chunkIndex = 1,
                    content = "Gross Revenue 325,290 293,829",
                    embedding = null,
                    chunkType = "TEXT",
                    parentChunkId = sectionId,
                    sectionId = sectionId
                ),
                ChunkEntity(
                    id = "child2",
                    docId = "doc",
                    fileName = "financial.pdf",
                    chunkIndex = 2,
                    content = "EBITDA 48,588 50,932",
                    embedding = null,
                    chunkType = "TEXT",
                    parentChunkId = sectionId,
                    sectionId = sectionId
                )
            ))
            dao.rebuildChunksFts()

            val retriever = HybridRetriever(dao, NoopEmbeddingService)
            val results = retriever.retrieve("Gross Revenue 325,290", maxResults = 1)

            assertTrue(results.any { it.chunkId == "child1" })
            assertTrue(results.any { it.chunkId == sectionId })
            assertTrue(results.any { it.chunkId == "child2" })
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
