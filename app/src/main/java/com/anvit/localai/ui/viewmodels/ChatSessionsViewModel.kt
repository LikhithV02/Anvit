package com.anvit.localai.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anvit.localai.AnvitApplication
import com.anvit.localai.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatSessionsUiState(
    val sessions: List<ChatSessionEntity> = emptyList(),
    val activeSessionId: String = ""
)

/**
 * Standalone ViewModel for managing chat sessions.
 * Session logic is also available directly in [ChatViewModel] for the main chat screen;
 * this VM exists for any screen that only needs the session list / creation / deletion UI
 * without pulling in the full inference machinery.
 */
class ChatSessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val app           = application as AnvitApplication
    private val chatDao       = app.database.chatDao()
    private val chatSessionDao = app.database.chatSessionDao()

    private val _uiState = MutableStateFlow(ChatSessionsUiState())
    val uiState: StateFlow<ChatSessionsUiState> = _uiState.asStateFlow()

    init {
        // Observe sessions list
        viewModelScope.launch {
            chatSessionDao.getAllSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }

        // Observe active session id
        viewModelScope.launch {
            app.preferences.activeSessionId.collect { id ->
                _uiState.update { it.copy(activeSessionId = id) }
            }
        }
    }

    /** Create a new blank session and activate it. */
    fun createNewSession() {
        viewModelScope.launch {
            val session = ChatSessionEntity(
                id        = UUID.randomUUID().toString(),
                title     = "New Chat",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            chatSessionDao.insertSession(session)
            app.preferences.setActiveSessionId(session.id)
        }
    }

    /** Delete a session and all its messages, switching to another session if needed. */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatDao.deleteMessagesForSession(sessionId)
            chatSessionDao.deleteSession(sessionId)
            if (sessionId == _uiState.value.activeSessionId) {
                val remaining = chatSessionDao.getAllSessionsList()
                if (remaining.isNotEmpty()) {
                    app.preferences.setActiveSessionId(remaining.first().id)
                } else {
                    createNewSession()
                }
            }
        }
    }

    /** Rename a session. */
    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            val session = chatSessionDao.getSession(sessionId) ?: return@launch
            chatSessionDao.updateSession(session.copy(title = newTitle.trim().take(60)))
        }
    }

    /** Switch the active session. */
    fun switchToSession(sessionId: String) {
        viewModelScope.launch {
            app.preferences.setActiveSessionId(sessionId)
        }
    }
}
