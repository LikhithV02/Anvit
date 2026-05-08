package com.anvit.localai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anvit.localai.utils.currentTimeMillis

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val agentSteps: String = "",
    val thinkingContent: String = "",
    val createdAt: Long = currentTimeMillis(),
    val imagePath: String? = null,
    val audioPath: String? = null,
    val usedSources: String = "",
    val isTranscribed: Boolean = false,
    val wasStopped: Boolean = false
)
