package com.sage.localai.ui.viewmodels

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sage.localai.SageApplication
import com.sage.localai.agentic.AgentStep
import com.sage.localai.agentic.AgenticRagOrchestrator
import com.sage.localai.data.db.entities.ChatMessageEntity
import com.sage.localai.data.db.entities.ChatSessionEntity
import com.sage.localai.data.db.entities.CollectionEntity
import com.sage.localai.data.models.GemmaModels
import com.sage.localai.data.preferences.SagePreferences
import com.sage.localai.document.ImageAttachmentManager
import com.sage.localai.inference.GemmaInferenceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SourceChunk(
    val title: String,
    val snippet: String
)

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val agentSteps: List<AgentStep> = emptyList(),
    val isStreaming: Boolean = false,
    val thinkingContent: String = "",
    val wasStopped: Boolean = false,
    val usedDirectMode: Boolean = false,
    val imagePath: String? = null,   // attached image (user messages)
    val usedSources: List<SourceChunk> = emptyList() // chunks used globally by RAG
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sessions: List<ChatSessionEntity> = emptyList(),
    val activeSessionId: String = "",
    val activeSessionTitle: String = "New Chat",
    val activeCollectionName: String = "General",
    val isGenerating: Boolean = false,
    val isModelLoaded: Boolean = false,
    val isAutoLoadingModel: Boolean = false,
    val autoLoadStatus: String = "",
    val errorMessage: String? = null,
    val inputText: String = "",
    // Live-streaming assistant message — updated per token to avoid O(n) list copies
    val streamingMessage: ChatMessage? = null,
    // Collection picker state
    val collections: List<CollectionEntity> = emptyList(),
    val chatCollectionId: String? = "default-collection",  // null = no collection → direct mode
    val chatCollectionName: String = "General",
    // Image attachment state
    val pendingImagePath: String? = null,  // image staged for the next send; null = none
    val loadedModelName: String = ""
) {
    /** Combined view: persisted messages + the in-flight streaming message (if any). */
    val allMessages: List<ChatMessage> get() =
        if (streamingMessage != null) messages + streamingMessage else messages
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SageApplication
    private val chatDao        = app.database.chatDao()
    private val chatSessionDao = app.database.chatSessionDao()

    private val orchestrator by lazy {
        AgenticRagOrchestrator(app.inferenceService, app.hybridRetriever, app.database.documentDao())
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Tracks the current session ID synchronously for use in sendMessage
    @Volatile private var currentSessionId: String = ""

    // Current generation job — cancelled by stopGeneration()
    private var generationJob: Job? = null

    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_HISTORY_MESSAGES = 10
    }

    init {
        // Observe all sessions for the drawer list
        viewModelScope.launch {
            chatSessionDao.getAllSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }

        // Observe active session ID and switch message collection reactively
        viewModelScope.launch {
            app.preferences.activeSessionId.flatMapLatest { sessionId ->
                currentSessionId = sessionId
                if (sessionId.isEmpty()) {
                    emptyFlow()
                } else {
                    // Update session title
                    val title = chatSessionDao.getSession(sessionId)?.title ?: "Chat"
                    _uiState.update { it.copy(activeSessionId = sessionId, activeSessionTitle = title) }
                    chatDao.getMessagesForSession(sessionId)
                }
            }.collect { entities ->
                val messages = entities.map { entity ->
                    ChatMessage(
                        id              = entity.id,
                        role            = entity.role,
                        content         = entity.content,
                        agentSteps      = parseAgentSteps(entity.agentSteps),
                        thinkingContent = entity.thinkingContent,
                        imagePath       = entity.imagePath,
                        usedSources     = parseSources(entity.usedSources)
                    )
                }
                _uiState.update { it.copy(messages = messages) }
            }
        }

        // Observe active collection name for the top bar chip
        viewModelScope.launch {
            app.preferences.activeCollectionId.collect { collectionId ->
                val name = app.database.collectionDao().getCollection(collectionId)?.name ?: "General"
                _uiState.update { it.copy(activeCollectionName = name) }
            }
        }

        // Observe all collections for the picker sheet
        viewModelScope.launch {
            app.database.collectionDao().getAllCollections().collect { list ->
                _uiState.update { it.copy(collections = list) }
            }
        }

        // Initialize chatCollectionId from the user's active collection preference
        viewModelScope.launch {
            val collectionId = app.preferences.getActiveCollectionId()
            val name = app.database.collectionDao().getCollection(collectionId)?.name ?: "General"
            _uiState.update { it.copy(chatCollectionId = collectionId, chatCollectionName = name) }
        }

        viewModelScope.launch { ensureDefaultSession() }
        checkModelLoaded()
    }

    // ── Session management ────────────────────────────────────────────────────

    private suspend fun ensureDefaultSession() {
        val sessions = chatSessionDao.getAllSessionsList()
        val activeId = app.preferences.activeSessionId.first()
        when {
            sessions.isEmpty() -> {
                val newSession = newSessionEntity("New Chat")
                chatSessionDao.insertSession(newSession)
                app.preferences.setActiveSessionId(newSession.id)
            }
            activeId.isEmpty() || sessions.none { it.id == activeId } -> {
                app.preferences.setActiveSessionId(sessions.first().id)
            }
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            val session = newSessionEntity("New Chat")
            chatSessionDao.insertSession(session)
            switchToSession(session.id)
        }
    }

    fun switchToSession(sessionId: String) {
        viewModelScope.launch {
            // Clear UI messages immediately for a clean visual transition
            _uiState.update { it.copy(messages = emptyList(), inputText = "") }
            app.preferences.setActiveSessionId(sessionId)
            val title = chatSessionDao.getSession(sessionId)?.title ?: "Chat"
            _uiState.update { it.copy(activeSessionId = sessionId, activeSessionTitle = title) }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatDao.deleteMessagesForSession(sessionId)
            chatSessionDao.deleteSession(sessionId)
            // If we deleted the active session, switch to the most recent remaining one
            if (sessionId == currentSessionId) {
                val remaining = chatSessionDao.getAllSessionsList()
                if (remaining.isNotEmpty()) {
                    switchToSession(remaining.first().id)
                } else {
                    createNewSession()
                }
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            val session = chatSessionDao.getSession(sessionId) ?: return@launch
            chatSessionDao.updateSession(session.copy(title = newTitle.trim().take(60)))
            if (sessionId == currentSessionId) {
                _uiState.update { it.copy(activeSessionTitle = newTitle.trim().take(60)) }
            }
        }
    }

    fun regenerateMessage(messageId: String) {
        if (_uiState.value.isGenerating) return
        val sessionId = currentSessionId
        if (sessionId.isEmpty()) return

        viewModelScope.launch {
            val msgs = uiState.value.messages
            val assistantIndex = msgs.indexOfFirst { it.id == messageId }
            if (assistantIndex == -1) return@launch
            
            val assistantMsg = msgs[assistantIndex]
            if (assistantMsg.role != "assistant") return@launch

            val userMsgIndex = assistantIndex - 1
            if (userMsgIndex < 0) return@launch
            val userMsg = msgs[userMsgIndex]
            if (userMsg.role != "user") return@launch

            // Delete the user message, the assistant message, and any later messages
            val subList = msgs.subList(userMsgIndex, msgs.size)
            subList.forEach { chatDao.deleteMessage(it.id) }
            
            // Re-stage the image if one was used
            _uiState.update { state -> 
                val newMsgs = state.messages.take(userMsgIndex)
                state.copy(
                    messages = newMsgs,
                    pendingImagePath = userMsg.imagePath
                )
            }
            
            sendMessage(userMsg.content)
        }
    }

    // ── Chat ─────────────────────────────────────────────────────────────────

    /** Switch the collection used for RAG in this chat. Pass null for direct (no-RAG) mode. */
    fun selectChatCollection(id: String?) {
        viewModelScope.launch {
            val name = if (id == null) "No docs"
                       else app.database.collectionDao().getCollection(id)?.name ?: "General"
            _uiState.update { it.copy(chatCollectionId = id, chatCollectionName = name) }
        }
    }

    /**
     * Copy an image from [uri] into stable internal storage and stage it for the next send.
     * Runs on IO to avoid blocking the main thread.
     */
    fun attachImage(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = ImageAttachmentManager.copyToStorage(context, uri)
                _uiState.update { it.copy(pendingImagePath = path) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach image: ${e.message}", e)
                _uiState.update { it.copy(errorMessage = "Could not attach image: ${e.message}") }
            }
        }
    }

    /** Remove the staged image without sending it. */
    fun clearAttachedImage() {
        _uiState.value.pendingImagePath?.let { ImageAttachmentManager.delete(it) }
        _uiState.update { it.copy(pendingImagePath = null) }
    }

    /**
     * Load the user's selected model (or the default if none set) without requiring
     * them to visit Settings first. Updates [isAutoLoadingModel] + [autoLoadStatus]
     * while work is in progress and clears them on completion.
     * @return true if the model loaded successfully, false otherwise.
     */
    private suspend fun autoLoadModel(): Boolean {
        val modelId = app.preferences.selectedModelId.first()
        val model   = GemmaModels.all.find { it.id == modelId }
                   ?: GemmaModels.all.firstOrNull { it.isDefault }
                   ?: GemmaModels.all.first()

        _uiState.update {
            it.copy(isAutoLoadingModel = true, autoLoadStatus = "Loading ${model.displayName}…")
        }

        val prefs = app.preferences
        app.inferenceService.setGenerationParams(
            topK           = prefs.topK.first(),
            topP           = 0.95f,
            temperature    = prefs.temperature.first(),
            enableThinking = prefs.enableThinking.first()
        )

        val ok = app.inferenceService.loadModel(model)
        _uiState.update {
            if (ok) {
                it.copy(
                    isAutoLoadingModel = false,
                    autoLoadStatus     = "",
                    isModelLoaded      = true,
                    loadedModelName    = model.displayName
                )
            } else {
                it.copy(
                    isAutoLoadingModel = false,
                    autoLoadStatus     = "",
                    isModelLoaded      = false,
                    loadedModelName    = "",
                    errorMessage       = "Could not load ${model.displayName}. " +
                                        "Make sure ${model.fileName} has been downloaded in Settings."
                )
            }
        }
        return ok
    }

    fun refreshModelState() {
        val model = app.inferenceService.getCurrentModel()
        _uiState.update {
            it.copy(
                isModelLoaded = model != null,
                loadedModelName = model?.displayName ?: ""
            )
        }
    }

    private fun checkModelLoaded() = refreshModelState()

    fun setInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /**
     * Cancels the current generation mid-stream.
     * The partially generated response is kept and saved to the DB so
     * the user can see what was produced before they stopped.
     */
    fun stopGeneration() {
        generationJob?.cancel()
        // isGenerating and isStreaming are cleared in the finally block of sendMessage()
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank() || _uiState.value.isGenerating) return
        val sessionId = currentSessionId
        if (sessionId.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No active session. Please create a new chat.") }
            return
        }

        // Capture and clear pending image before launching the coroutine
        val capturedImagePath = _uiState.value.pendingImagePath
        _uiState.update { it.copy(pendingImagePath = null) }

        generationJob = viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, inputText = "", errorMessage = null) }

            // ── Auto-load model if not ready ──────────────────────────────────
            if (!app.inferenceService.isLoaded()) {
                val loaded = autoLoadModel()
                if (!loaded) {
                    _uiState.update { it.copy(isGenerating = false) }
                    return@launch
                }
            }

            // Vision model guard (checked after load so we know the actual model)
            if (capturedImagePath != null) {
                val model = app.inferenceService.getCurrentModel()
                if (model != null && !model.supportsVision) {
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            errorMessage = "${model.displayName} doesn't support images. Load a Gemma 4 vision model first."
                        )
                    }
                    return@launch
                }
            }

            // Save user message (include imagePath so it persists in history)
            val userMsgId = UUID.randomUUID().toString()
            chatDao.insertMessage(
                ChatMessageEntity(
                    id        = userMsgId,
                    sessionId = sessionId,
                    role      = "user",
                    content   = userText,
                    imagePath = capturedImagePath
                )
            )

            // Auto-title session from first user message
            autoTitleSession(sessionId, userText)

            // Build conversation history (scoped to current session)
            val history = buildHistory(sessionId)

            // Create streaming assistant placeholder (tracked in streamingMessage, not messages list)
            val assistantMsgId = UUID.randomUUID().toString()
            _uiState.update { state ->
                state.copy(
                    streamingMessage = ChatMessage(assistantMsgId, "assistant", "", isStreaming = true)
                )
            }

            // Mutable state shared between try and finally
            val fullResponse    = StringBuilder()
            val fullThinking    = StringBuilder()
            var agentStepsList  = emptyList<AgentStep>()
            var sourcesList     = emptyList<SourceChunk>()
            var messageSaved    = false
            var wasCancelled    = false
            var usedDirectMode  = false

            try {
                val prefs = app.preferences
                val enableAgenticRag   = prefs.enableAgenticRag.first()
                val maxChunks          = prefs.maxRetrievalChunks.first()
                val maxTokens          = prefs.maxOutputTokens.first()
                val enableSelfCritique = prefs.enableSelfCritique.first()
                val topK               = prefs.topK.first()
                val temperature        = prefs.temperature.first()
                val enableThinking     = prefs.enableThinking.first()
                // Use per-chat collection (may be null = direct/no-RAG mode)
                val collectionId       = _uiState.value.chatCollectionId

                app.inferenceService.setGenerationParams(
                    topK = topK, temperature = temperature, enableThinking = enableThinking, maxTokens = maxTokens
                )

                // Choose flow source: direct (no RAG) or orchestrator
                val answerFlow: Flow<String>
                if (collectionId == null) {
                    usedDirectMode = true
                    val directPrompt = if (history.isNotEmpty()) "$history\nuser: $userText" else userText
                    answerFlow = app.inferenceService.generateStream(
                        prompt        = directPrompt,
                        systemPrompt  = "You are Sage, a helpful and concise AI assistant.",
                        useAgentTools = false,
                        imagePath     = capturedImagePath
                    )
                } else {
                    val result = orchestrator.process(
                        userQuery           = userText,
                        conversationHistory = history,
                        enableAgenticRag    = enableAgenticRag,
                        maxChunks           = maxChunks,
                        enableSelfCritique  = enableSelfCritique,
                        useAgentTools       = true,
                        collectionId        = collectionId,
                        imagePath           = capturedImagePath,
                        onStep              = { step ->
                            agentStepsList = agentStepsList + step
                            _uiState.update { state ->
                                state.copy(
                                    streamingMessage = (state.streamingMessage
                                        ?: ChatMessage(assistantMsgId, "assistant", "", isStreaming = true))
                                        .copy(agentSteps = agentStepsList)
                                )
                            }
                        },
                        onSources           = { chunks ->
                            sourcesList = chunks.map { SourceChunk(it.fileName, it.content) }
                            _uiState.update { state ->
                                state.copy(
                                    streamingMessage = (state.streamingMessage
                                        ?: ChatMessage(assistantMsgId, "assistant", "", isStreaming = true))
                                        .copy(usedSources = sourcesList)
                                )
                            }
                        }
                    )
                    agentStepsList = result.steps
                    _uiState.update { state ->
                        state.copy(
                            streamingMessage = (state.streamingMessage
                                ?: ChatMessage(assistantMsgId, "assistant", "", isStreaming = true))
                                .copy(agentSteps = result.steps, usedSources = sourcesList)
                        )
                    }
                    answerFlow = result.answerFlow
                }

                var thinkOpen = false

                answerFlow.collect { chunk ->
                    when (chunk) {
                        GemmaInferenceService.SENTINEL_THINK    -> { thinkOpen = true }
                        GemmaInferenceService.SENTINEL_ENDTHINK -> { thinkOpen = false }
                        else -> {
                            if (thinkOpen) {
                                fullThinking.append(chunk)
                                // Update only the streaming message — O(1), not O(n)
                                _uiState.update { state ->
                                    state.copy(
                                        streamingMessage = (state.streamingMessage
                                            ?: ChatMessage(assistantMsgId, "assistant", "", isStreaming = true))
                                            .copy(thinkingContent = fullThinking.toString(), isStreaming = true)
                                    )
                                }
                            } else {
                                fullResponse.append(chunk)
                                // Update only the streaming message — O(1), not O(n)
                                _uiState.update { state ->
                                    state.copy(
                                        streamingMessage = (state.streamingMessage
                                            ?: ChatMessage(assistantMsgId, "assistant", "", isStreaming = true))
                                            .copy(content = fullResponse.toString(), isStreaming = true)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Normal completion — save to DB ────────────────────────────
                val finalContent  = fullResponse.toString().trim()
                val finalThinking = fullThinking.toString().trim()
                chatDao.insertMessage(
                    ChatMessageEntity(
                        id              = assistantMsgId,
                        sessionId       = sessionId,
                        role            = "assistant",
                        content         = finalContent,
                        agentSteps      = serializeAgentSteps(agentStepsList),
                        thinkingContent = finalThinking,
                        usedSources     = serializeSources(sourcesList)
                    )
                )
                messageSaved = true

                // Update session metadata
                val msgCount = chatDao.getMessagesForSessionList(sessionId).size
                chatSessionDao.getSession(sessionId)?.let { s ->
                    chatSessionDao.updateSession(s.copy(updatedAt = System.currentTimeMillis(), messageCount = msgCount))
                }

                // Move streaming message into the persisted list, clear streamingMessage
                val finalMsg = ChatMessage(
                    id              = assistantMsgId,
                    role            = "assistant",
                    content         = finalContent,
                    thinkingContent = finalThinking,
                    agentSteps      = agentStepsList,
                    isStreaming     = false,
                    wasStopped      = false,
                    usedDirectMode  = usedDirectMode,
                    usedSources     = sourcesList
                )
                _uiState.update { state ->
                    state.copy(
                        messages         = state.messages + finalMsg,
                        streamingMessage = null,
                        isGenerating     = false
                    )
                }

            } catch (e: CancellationException) {
                // User pressed Stop — mark as cancelled for the finally block
                wasCancelled = true
                throw e  // Re-throw so the coroutine is properly cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed: ${e.message}", e)
                // Move partial streamed content into messages list with error, clear streamingMessage
                val errorContent = if (fullResponse.isNotEmpty()) fullResponse.toString().trim()
                                   else "⚠️ Error: ${e.message}"
                val errorMsg = ChatMessage(
                    id          = assistantMsgId,
                    role        = "assistant",
                    content     = errorContent,
                    isStreaming = false
                )
                _uiState.update { state ->
                    state.copy(
                        messages         = state.messages + errorMsg,
                        streamingMessage = null,
                        isGenerating     = false,
                        errorMessage     = e.message
                    )
                }
                messageSaved = false  // Don't try to save again in finally
            } finally {
                // ── Always runs — handles cancellation cleanup with NonCancellable ──
                withContext(NonCancellable) {
                    if (!messageSaved) {
                        val partialContent  = fullResponse.toString().trim()
                        val partialThinking = fullThinking.toString().trim()
                        if (partialContent.isNotEmpty() || partialThinking.isNotEmpty()) {
                            // Save whatever was generated before stopping
                            chatDao.insertMessage(
                                ChatMessageEntity(
                                    id              = assistantMsgId,
                                    sessionId       = sessionId,
                                    role            = "assistant",
                                    content         = partialContent,
                                    agentSteps      = serializeAgentSteps(agentStepsList),
                                    thinkingContent = partialThinking,
                                    usedSources     = serializeSources(sourcesList)
                                )
                            )
                            val partialMsg = ChatMessage(
                                id              = assistantMsgId,
                                role            = "assistant",
                                content         = partialContent,
                                thinkingContent = partialThinking,
                                agentSteps      = agentStepsList,
                                isStreaming     = false,
                                wasStopped      = wasCancelled,
                                usedDirectMode  = usedDirectMode,
                                usedSources     = sourcesList
                            )
                            _uiState.update { state ->
                                state.copy(
                                    messages         = state.messages + partialMsg,
                                    streamingMessage = null,
                                    isGenerating     = false
                                )
                            }
                        } else if (wasCancelled) {
                            // Stopped before any tokens arrived
                            val stopMsg = "⚠️ Generation stopped by user."
                            chatDao.insertMessage(
                                ChatMessageEntity(
                                    id              = assistantMsgId,
                                    sessionId       = sessionId,
                                    role            = "assistant",
                                    content         = stopMsg,
                                    agentSteps      = serializeAgentSteps(agentStepsList),
                                    thinkingContent = fullThinking.toString().trim(),
                                    usedSources     = serializeSources(sourcesList)
                                )
                            )
                            val partialMsg = ChatMessage(
                                id              = assistantMsgId,
                                role            = "assistant",
                                content         = stopMsg,
                                thinkingContent = fullThinking.toString().trim(),
                                agentSteps      = agentStepsList,
                                isStreaming     = false,
                                wasStopped      = true,
                                usedDirectMode  = usedDirectMode,
                                usedSources     = sourcesList
                            )
                            _uiState.update { state ->
                                state.copy(
                                    messages         = state.messages + partialMsg,
                                    streamingMessage = null,
                                    isGenerating     = false
                                )
                            }
                        } else {
                            // Error path — isGenerating already cleared in catch block
                            _uiState.update { state ->
                                state.copy(streamingMessage = null, isGenerating = false)
                            }
                        }
                    }
                }
            }
        }
    }

    fun clearCurrentChat() {
        viewModelScope.launch {
            val sessionId = currentSessionId
            if (sessionId.isNotEmpty()) {
                chatDao.deleteMessagesForSession(sessionId)
                chatSessionDao.getSession(sessionId)?.let { s ->
                    chatSessionDao.updateSession(s.copy(messageCount = 0, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun buildHistory(sessionId: String): String {
        val messages = chatDao.getMessagesForSessionList(sessionId).takeLast(MAX_HISTORY_MESSAGES)
        return messages.joinToString("\n") { "${it.role}: ${it.content}" }
    }

    private suspend fun autoTitleSession(sessionId: String, firstMessage: String) {
        val session = chatSessionDao.getSession(sessionId) ?: return
        if (session.title == "New Chat" && session.messageCount == 0) {
            val autoTitle = firstMessage.take(45).let { if (it.length == 45) "$it…" else it }
            chatSessionDao.updateSession(session.copy(title = autoTitle))
            _uiState.update { it.copy(activeSessionTitle = autoTitle) }
        }
    }

    private fun newSessionEntity(title: String) = ChatSessionEntity(
        id        = UUID.randomUUID().toString(),
        title     = title,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    private fun serializeAgentSteps(steps: List<AgentStep>): String =
        steps.joinToString("|") { "${it.type}::${it.description}" }

    private fun parseAgentSteps(raw: String): List<AgentStep> {
        if (raw.isBlank()) return emptyList()
        return raw.split("|").mapNotNull {
            val parts = it.split("::", limit = 2)
            if (parts.size == 2) AgentStep(parts[0], parts[1]) else null
        }
    }

    private fun serializeSources(sources: List<SourceChunk>): String {
        if (sources.isEmpty()) return ""
        val array = JSONArray()
        for (s in sources) {
            val obj = JSONObject()
            obj.put("title", s.title)
            obj.put("snippet", s.snippet)
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseSources(raw: String): List<SourceChunk> {
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<SourceChunk>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(SourceChunk(obj.optString("title", ""), obj.optString("snippet", "")))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
