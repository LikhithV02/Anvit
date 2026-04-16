package com.anvit.localai.data.db

import androidx.room.*
import com.anvit.localai.data.db.entities.ChunkEntity
import com.anvit.localai.data.db.entities.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    // Collection-scoped document queries
    @Query("SELECT * FROM documents WHERE collectionId = :collectionId ORDER BY createdAt DESC")
    fun getDocumentsForCollection(collectionId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocumentById(id: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: DocumentEntity)

    @Update
    suspend fun updateDocument(doc: DocumentEntity)

    @Delete
    suspend fun deleteDocument(doc: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocumentById(id: String)

    @Query("DELETE FROM documents WHERE collectionId = :collectionId")
    suspend fun deleteDocumentsForCollection(collectionId: String)

    // Chunk queries
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: ChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM chunks WHERE docId = :docId ORDER BY chunkIndex ASC")
    suspend fun getChunksForDocument(docId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks ORDER BY docId, chunkIndex ASC")
    suspend fun getAllChunks(): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE collectionId = :collectionId ORDER BY docId, chunkIndex ASC")
    suspend fun getChunksForCollection(collectionId: String): List<ChunkEntity>

    @Query("DELETE FROM chunks WHERE docId = :docId")
    suspend fun deleteChunksForDocument(docId: String)

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun getTotalChunkCount(): Int

    @Query("SELECT COUNT(*) FROM chunks WHERE collectionId = :collectionId")
    suspend fun getChunkCountForCollection(collectionId: String): Int

    // FTS search — global
    @Query("SELECT c.* FROM chunks c INNER JOIN chunks_fts fts ON c.rowid = fts.rowid WHERE chunks_fts MATCH :query LIMIT :limit")
    suspend fun searchChunksFts(query: String, limit: Int = 10): List<ChunkEntity>

    // FTS search — collection-scoped
    @Query("SELECT c.* FROM chunks c INNER JOIN chunks_fts fts ON c.rowid = fts.rowid WHERE chunks_fts MATCH :query AND c.collectionId = :collectionId LIMIT :limit")
    suspend fun searchChunksFtsForCollection(query: String, collectionId: String, limit: Int = 10): List<ChunkEntity>
}
