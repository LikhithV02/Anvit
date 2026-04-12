package com.sage.localai.document

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sage.localai.data.db.DocumentDao
import com.sage.localai.data.db.entities.ChunkEntity
import com.sage.localai.data.db.entities.DocumentEntity
import com.sage.localai.data.preferences.SagePreferences
import com.sage.localai.embedding.EmbeddingService
import com.sage.localai.utils.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class IngestionResult {
    data class Success(val docId: String, val chunkCount: Int, val pageCount: Int) : IngestionResult()
    data class Error(val message: String) : IngestionResult()
}

class DocumentIngestionService(
    private val context: Context,
    private val documentDao: DocumentDao,
    private val embeddingService: EmbeddingService
) {
    companion object {
        private const val TAG = "DocumentIngestion"
        private const val MAX_CHUNK_SIZE = 800
        private const val CHUNK_OVERLAP = 100
    }

    suspend fun ingestPdf(
        uri: Uri,
        fileName: String,
        collectionId: String = SagePreferences.DEFAULT_COLLECTION_ID,
        onProgress: (String) -> Unit = {}
    ): IngestionResult = withContext(Dispatchers.IO) {
        val docId = UUID.randomUUID().toString()

        try {
            documentDao.insertDocument(
                DocumentEntity(
                    id = docId,
                    fileName = fileName,
                    filePath = uri.toString(),
                    pageCount = 0,
                    chunkCount = 0,
                    status = "PROCESSING",
                    collectionId = collectionId
                )
            )

            onProgress("Extracting text from PDF...")
            val extraction = PdfProcessor.extractFromUri(context, uri, fileName)
                ?: return@withContext IngestionResult.Error(
                    "Failed to extract text from PDF. Ensure the file is a valid, text-based PDF."
                )

            if (extraction.text.isBlank()) {
                documentDao.updateDocument(
                    DocumentEntity(id = docId, fileName = fileName, filePath = uri.toString(),
                        pageCount = extraction.pageCount, chunkCount = 0, status = "FAILED",
                        collectionId = collectionId)
                )
                return@withContext IngestionResult.Error(
                    "PDF appears to be empty or image-only (scanned). Only text-based PDFs are supported."
                )
            }

            onProgress("Chunking text (${extraction.pageCount} pages)...")
            val chunks = DocumentChunker.chunk(extraction.text, MAX_CHUNK_SIZE, CHUNK_OVERLAP)
            Log.d(TAG, "Created ${chunks.size} chunks from ${extraction.pageCount} pages")

            onProgress("Initializing embedding model...")
            if (!embeddingService.isInitialized()) {
                val ok = embeddingService.initialize()
                if (!ok) {
                    documentDao.updateDocument(
                        DocumentEntity(id = docId, fileName = fileName, filePath = uri.toString(),
                            pageCount = extraction.pageCount, chunkCount = 0, status = "FAILED",
                            collectionId = collectionId)
                    )
                    return@withContext IngestionResult.Error(
                        "Embedding model not found.\n\nPlease place an embedding model " +
                        "(e.g. embeddinggemma-300M_seq2048_mixed-precision.tflite) in " +
                        "Android/data/com.sage.localai/files/models/"
                    )
                }
            }

            var embeddedCount = 0
            val chunkEntities = mutableListOf<ChunkEntity>()

            for ((index, chunkText) in chunks.withIndex()) {
                if (index % 5 == 0) onProgress("Embedding chunks... ${index + 1}/${chunks.size}")
                val embedding = embeddingService.generateEmbedding(chunkText)
                chunkEntities.add(
                    ChunkEntity(
                        id = "${docId}_$index",
                        docId = docId,
                        fileName = fileName,
                        chunkIndex = index,
                        content = chunkText,
                        embedding = embedding?.toByteArray(),
                        collectionId = collectionId
                    )
                )
                if (embedding != null) embeddedCount++
            }

            onProgress("Saving to database...")
            documentDao.insertChunks(chunkEntities)

            documentDao.updateDocument(
                DocumentEntity(
                    id = docId,
                    fileName = fileName,
                    filePath = uri.toString(),
                    pageCount = extraction.pageCount,
                    chunkCount = embeddedCount,
                    status = "READY",
                    collectionId = collectionId,
                    sizeBytes = try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L
                    } catch (_: Exception) { 0L }
                )
            )

            Log.d(TAG, "Ingestion complete: $embeddedCount/${chunks.size} chunks for $fileName in collection $collectionId")
            IngestionResult.Success(docId, embeddedCount, extraction.pageCount)

        } catch (e: Exception) {
            Log.e(TAG, "Ingestion failed for $fileName: ${e.message}", e)
            try {
                documentDao.updateDocument(
                    DocumentEntity(id = docId, fileName = fileName, filePath = uri.toString(),
                        pageCount = 0, chunkCount = 0, status = "FAILED", collectionId = collectionId)
                )
            } catch (_: Exception) {}
            IngestionResult.Error("Ingestion failed: ${e.message}")
        }
    }

    suspend fun deleteDocument(docId: String) {
        documentDao.deleteDocumentById(docId)
        Log.d(TAG, "Deleted document and chunks for docId=$docId")
    }
}
