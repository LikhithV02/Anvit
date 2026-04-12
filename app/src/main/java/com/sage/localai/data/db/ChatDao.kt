package com.sage.localai.data.db

import androidx.room.*
import com.sage.localai.data.db.entities.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // Session-scoped queries (primary use)
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getMessagesForSessionList(sessionId: String): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    // Global queries (kept for migration / admin use)
    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC")
    fun getAllMessages(): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: String)
}
