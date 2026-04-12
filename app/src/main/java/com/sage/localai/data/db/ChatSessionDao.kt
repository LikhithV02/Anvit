package com.sage.localai.data.db

import androidx.room.*
import com.sage.localai.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    suspend fun getAllSessionsList(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getSession(id: String): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)
}
