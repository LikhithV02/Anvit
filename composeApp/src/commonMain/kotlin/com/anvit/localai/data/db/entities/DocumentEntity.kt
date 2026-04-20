package com.anvit.localai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anvit.localai.utils.currentTimeMillis

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val filePath: String,
    val pageCount: Int,
    val chunkCount: Int,
    val status: String,
    val createdAt: Long = currentTimeMillis(),
    val sizeBytes: Long = 0L,
    val collectionId: String = "default-collection"
)
