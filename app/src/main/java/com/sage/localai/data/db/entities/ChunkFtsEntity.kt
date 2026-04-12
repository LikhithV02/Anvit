package com.sage.localai.data.db.entities

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = ChunkEntity::class)
@Entity(tableName = "chunks_fts")
data class ChunkFtsEntity(
    val content: String
)
