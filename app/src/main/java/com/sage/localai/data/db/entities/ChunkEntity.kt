package com.sage.localai.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("docId")]
)
data class ChunkEntity(
    @PrimaryKey val id: String,           // "{docId}_{chunkIndex}"
    val docId: String,
    val fileName: String,
    val chunkIndex: Int,
    val content: String,
    val embedding: ByteArray?,            // float[] serialized as ByteArray
    val createdAt: Long = System.currentTimeMillis(),
    val collectionId: String = "default-collection"
)

// FTS5 virtual table for BM25 lexical search
// Must be a separate @Entity annotated with @Fts4
