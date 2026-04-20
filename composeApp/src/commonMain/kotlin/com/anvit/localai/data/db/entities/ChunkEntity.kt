package com.anvit.localai.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.anvit.localai.utils.currentTimeMillis

@Entity(
    tableName = "chunks",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["docId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("docId")]
)
data class ChunkEntity(
    @PrimaryKey val id: String,
    val docId: String,
    val fileName: String,
    val chunkIndex: Int,
    val content: String,
    val embedding: ByteArray?,
    val createdAt: Long = currentTimeMillis(),
    val collectionId: String = "default-collection"
)
