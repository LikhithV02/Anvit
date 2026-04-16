package com.anvit.localai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,           // FK to chat_sessions.id
    val role: String,                // "user" or "assistant"
    val content: String,
    val agentSteps: String = "",     // serialized agent steps
    val thinkingContent: String = "", // <think>...</think> from Gemma 4
    val createdAt: Long = System.currentTimeMillis(),
    val imagePath: String? = null,  // absolute path inside filesDir/chat_images/; null = no image
    val audioPath: String? = null,  // absolute path inside filesDir/chat_audio/; null = no audio
    val usedSources: String = "",   // serialized list of sources used for generating this message
    val isTranscribed: Boolean = false // true when content was auto-transcribed from audio (no typed text)
)
