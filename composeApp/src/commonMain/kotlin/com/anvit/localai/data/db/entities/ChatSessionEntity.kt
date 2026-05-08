package com.anvit.localai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anvit.localai.utils.currentTimeMillis

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long = currentTimeMillis(),
    val updatedAt: Long = currentTimeMillis(),
    val messageCount: Int = 0,
    val isPinned: Boolean = false,
)
