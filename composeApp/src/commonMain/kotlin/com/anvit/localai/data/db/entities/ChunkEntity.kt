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
    indices = [
        Index("docId"),
        Index("groupId"),
        Index("parentChunkId"),
        Index("sectionId"),
        Index("chunkType"),
        Index(value = ["pageStart", "pageEnd"])
    ]
)
data class ChunkEntity(
    @PrimaryKey val id: String,
    val docId: String,
    val fileName: String,
    val chunkIndex: Int,
    val content: String,
    val embedding: ByteArray?,
    val createdAt: Long = currentTimeMillis(),
    val collectionId: String = "default-collection",
    val hierarchyPath: String = "",
    val groupId: String? = null,
    val isGroupHead: Boolean = false,
    val chunkType: String = "TEXT",
    val parentChunkId: String? = null,
    val sectionId: String? = null,
    val pageStart: Int = 0,
    val pageEnd: Int = 0,
    val bboxJson: String = "",
    val rowRangeJson: String = ""
)
