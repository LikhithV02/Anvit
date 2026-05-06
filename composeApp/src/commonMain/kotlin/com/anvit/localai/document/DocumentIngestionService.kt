package com.anvit.localai.document

import com.anvit.localai.data.db.DocumentDao
import com.anvit.localai.data.db.entities.ChunkEntity
import com.anvit.localai.data.db.entities.DocumentEntity
import com.anvit.localai.data.preferences.AnvitPreferences
import com.anvit.localai.embedding.EmbeddingService
import com.anvit.localai.utils.randomUUID
import com.anvit.localai.utils.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

sealed class IngestionResult {
    data class Success(val docId: String, val chunkCount: Int, val pageCount: Int) : IngestionResult()
    data class Error(val message: String) : IngestionResult()
}

class DocumentIngestionService(
    private val documentDao: DocumentDao,
    private val embeddingService: EmbeddingService,
    private val pdfExtractor: PdfExtractor,
    private val foregroundController: IngestionForegroundController = IngestionForegroundController.NoOp,
    private val documentParsers: List<DocumentParser> = emptyList()
) {
    companion object {
        private const val MAX_CHUNK_SIZE = 800
        private const val CHUNK_OVERLAP  = 100
        private const val MAX_TOKENS_PER_CHUNK = 8000
    }

    suspend fun ingestDocument(
        fileName: String,
        documentBytes: ByteArray,
        collectionId: String = AnvitPreferences.DEFAULT_COLLECTION_ID,
        onProgress: (String) -> Unit = {}
    ): IngestionResult = withContext(Dispatchers.IO) {
        val docId = randomUUID()
        val report: (Float, String) -> Unit = { fraction, text ->
            onProgress(text)
            foregroundController.update(fraction, text)
        }

        foregroundController.start("Preparing $fileName")
        try {
            documentDao.insertDocument(DocumentEntity(
                id = docId, fileName = fileName, filePath = "",
                pageCount = 0, chunkCount = 0, status = "PROCESSING",
                collectionId = collectionId
            ))

            val structuredParser = documentParsers.firstOrNull { it.supports(fileName) }

            if (structuredParser != null) {
                // ── Hierarchical path (PDF via pdfbox-android, DOCX via POI) ──────────
                report(0.05f, "Parsing document structure...")
                val root = structuredParser.parse(fileName, documentBytes)

                report(0.10f, "Building section chunks...")
                val docChunks = HierarchicalChunker(MAX_TOKENS_PER_CHUNK).chunkTree(root)

                if (docChunks.isEmpty()) {
                    documentDao.updateDocument(DocumentEntity(
                        id = docId, fileName = fileName, filePath = "",
                        pageCount = 0, chunkCount = 0, status = "FAILED", collectionId = collectionId
                    ))
                    return@withContext IngestionResult.Error(
                        "Document appears to be empty or image-only. Only text-based documents are supported."
                    )
                }

                report(0.15f, "Initializing embedding model...")
                if (!embeddingService.isInitialized()) {
                    val ok = embeddingService.initialize()
                    if (!ok) {
                        documentDao.updateDocument(DocumentEntity(
                            id = docId, fileName = fileName, filePath = "",
                            pageCount = 0, chunkCount = 0, status = "FAILED", collectionId = collectionId
                        ))
                        return@withContext IngestionResult.Error(
                            "Embedding model not found. Please download an embedding model in Settings."
                        )
                    }
                }

                var embeddedCount = 0
                val chunkEntities = mutableListOf<ChunkEntity>()
                val chunkCount = docChunks.size.coerceAtLeast(1)
                for ((index, docChunk) in docChunks.withIndex()) {
                    if (index % 5 == 0) {
                        val embedFraction = 0.15f + 0.80f * (index.toFloat() / chunkCount)
                        report(embedFraction, "Embedding chunks... ${index + 1}/${docChunks.size}")
                    }
                    val embedding = embeddingService.generateEmbedding(docChunk.content)
                    chunkEntities.add(
                        docChunk.toChunkEntity(docId, fileName, index, embedding?.toByteArray(), collectionId)
                    )
                    if (embedding != null) embeddedCount++
                }

                report(0.98f, "Saving to database...")
                documentDao.insertChunks(chunkEntities)
                documentDao.rebuildChunksFts()
                documentDao.updateDocument(DocumentEntity(
                    id = docId, fileName = fileName, filePath = "",
                    pageCount = 0, chunkCount = embeddedCount,
                    status = "READY", collectionId = collectionId,
                    sizeBytes = documentBytes.size.toLong()
                ))
                IngestionResult.Success(docId, embeddedCount, 0)

            } else {
                // ── Legacy flat-text path (iOS PDF via pdfExtractor, fallback) ────────
                report(0.05f, "Extracting text from PDF...")
                val extraction = pdfExtractor.extract(fileName, documentBytes)
                    ?: return@withContext IngestionResult.Error("Failed to extract text from PDF.")

                if (extraction.text.isBlank()) {
                    documentDao.updateDocument(DocumentEntity(
                        id = docId, fileName = fileName, filePath = "",
                        pageCount = extraction.pageCount, chunkCount = 0,
                        status = "FAILED", collectionId = collectionId
                    ))
                    return@withContext IngestionResult.Error(
                        "PDF appears to be empty or image-only (scanned). Only text-based PDFs are supported."
                    )
                }

                report(0.10f, "Chunking text (${extraction.pageCount} pages)...")
                val chunks = DocumentChunker.chunk(extraction.text, MAX_CHUNK_SIZE, CHUNK_OVERLAP)

                report(0.15f, "Initializing embedding model...")
                if (!embeddingService.isInitialized()) {
                    val ok = embeddingService.initialize()
                    if (!ok) {
                        documentDao.updateDocument(DocumentEntity(
                            id = docId, fileName = fileName, filePath = "",
                            pageCount = extraction.pageCount, chunkCount = 0,
                            status = "FAILED", collectionId = collectionId
                        ))
                        return@withContext IngestionResult.Error(
                            "Embedding model not found. Please download an embedding model in Settings."
                        )
                    }
                }

                var embeddedCount = 0
                val chunkEntities = mutableListOf<ChunkEntity>()
                val chunkCount = chunks.size.coerceAtLeast(1)
                for ((index, chunkText) in chunks.withIndex()) {
                    if (index % 5 == 0) {
                        val embedFraction = 0.15f + 0.80f * (index.toFloat() / chunkCount)
                        report(embedFraction, "Embedding chunks... ${index + 1}/${chunks.size}")
                    }
                    val embedding = embeddingService.generateEmbedding(chunkText)
                    chunkEntities.add(ChunkEntity(
                        id = "${docId}_$index", docId = docId, fileName = fileName,
                        chunkIndex = index, content = chunkText,
                        embedding = embedding?.toByteArray(),
                        collectionId = collectionId
                    ))
                    if (embedding != null) embeddedCount++
                }

                report(0.98f, "Saving to database...")
                documentDao.insertChunks(chunkEntities)
                documentDao.rebuildChunksFts()
                documentDao.updateDocument(DocumentEntity(
                    id = docId, fileName = fileName, filePath = "",
                    pageCount = extraction.pageCount, chunkCount = embeddedCount,
                    status = "READY", collectionId = collectionId,
                    sizeBytes = documentBytes.size.toLong()
                ))
                IngestionResult.Success(docId, embeddedCount, extraction.pageCount)
            }

        } catch (e: Exception) {
            println("[DocumentIngestion] Failed for $fileName: ${e.message}")
            try {
                documentDao.updateDocument(DocumentEntity(
                    id = docId, fileName = fileName, filePath = "",
                    pageCount = 0, chunkCount = 0, status = "FAILED", collectionId = collectionId
                ))
            } catch (_: Exception) {}
            IngestionResult.Error("Ingestion failed: ${e.message}")
        } finally {
            foregroundController.stop()
        }
    }

    suspend fun deleteDocument(docId: String) {
        documentDao.deleteDocumentById(docId)
    }
}
