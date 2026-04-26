@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.anvit.localai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvit.localai.agentic.AgentStep
import com.anvit.localai.agentic.AgenticRagOrchestrator
import com.anvit.localai.data.db.ChatDao
import com.anvit.localai.data.db.ChatSessionDao
import com.anvit.localai.data.db.CollectionDao
import com.anvit.localai.data.db.entities.ChatMessageEntity
import com.anvit.localai.data.db.entities.ChatSessionEntity
import com.anvit.localai.data.db.entities.CollectionEntity
import com.anvit.localai.data.models.GemmaModels
import com.anvit.localai.data.preferences.AnvitPreferences
import com.anvit.localai.data.reporting.ReportingService
import com.anvit.localai.inference.InferenceService
import com.anvit.localai.retrieval.HybridRetriever
import com.anvit.localai.utils.currentTimeMillis
import com.anvit.localai.utils.isIosPlatform
import com.anvit.localai.utils.randomUUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SourceChunk(val title: String, val snippet: String)

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val agentSteps: List<AgentStep> = emptyList(),
    val isStreaming: Boolean = false,
    val thinkingContent: String = "",
    val wasStopped: Boolean = false,
    val usedDirectMode: Boolean = false,
    val imagePath: String? = null,
    val audioPath: String? = null,
    val isTranscribed: Boolean = false,
    val usedSources: List<SourceChunk> = emptyList()
) {
    val provenanceId: String get() = id.replace("-", "").take(8).uppercase()
}

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
    val streamingMessage: ChatMessage? = null,
    val collections: List<CollectionEntity> = emptyList(),
    val chatCollectionId: String? = "default-collection",
    val chatCollectionName: String = "General",
    val pendingImagePath: String? = null,
    val pendingAudioPath: String? = null,
    val loadedModelName: String = "",
    val enableThinking: Boolean = true,
    val showComplianceReminder: Boolean = false,
    val userEmail: String = ""
) {
    val allMessages: List<ChatMessage> get() =
        if (streamingMessage != null) messages + streamingMessage else messages
}

class ChatViewModel(
    private val inferenceService: InferenceService,
    private val preferences: AnvitPreferences,
    private val chatDao: ChatDao,
    private val chatSessionDao: ChatSessionDao,
    private val orchestrator: AgenticRagOrchestrator,
    private val collectionDao: CollectionDao,
    private val reportingService: ReportingService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentSessionId: String = ""
    private var generationJob: Job? = null


    companion object {
        private const val MAX_HISTORY_MESSAGES = 10
    }

    init {
        viewModelScope.launch {
            chatSessionDao.getAllSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
        viewModelScope.launch {
            preferences.activeSessionId.flatMapLatest { sessionId ->
                currentSessionId = sessionId
                if (sessionId.isEmpty()) emptyFlow()
                else {
                    val title = chatSessionDao.getSession(sessionId)?.title ?: "Chat"
                    _uiState.update { it.copy(activeSessionId = sessionId, activeSessionTitle = title) }
                    chatDao.getMessagesForSession(sessionId)
                }
            }.collect { entities ->
                val newMessages = entities.map { e ->
                    ChatMessage(e.id, e.role, e.content, parseAgentSteps(e.agentSteps),
                        thinkingContent = e.thinkingContent, imagePath = e.imagePath,
                        audioPath = e.audioPath, isTranscribed = e.isTranscribed,
                        usedSources = parseSources(e.usedSources))
                }
                val dbIds = newMessages.map { it.id }.toSet()
                _uiState.update { s ->
                    // Clear streamingMessage if the DB already contains a message with the same id
                    // to avoid duplicate LazyColumn keys (crashes Compose).
                    val clearedStreaming = if (s.streamingMessage != null && s.streamingMessage.id in dbIds) null else s.streamingMessage
                    s.copy(messages = newMessages, streamingMessage = clearedStreaming)
                }
            }
        }
        viewModelScope.launch {
            preferences.activeCollectionId.collect { collectionId ->
                val name = collectionDao.getCollection(collectionId)?.name ?: "General"
                _uiState.update { it.copy(activeCollectionName = name) }
            }
        }
        viewModelScope.launch {
            collectionDao.getAllCollections().collect { list ->
                _uiState.update { it.copy(collections = list) }
            }
        }
        viewModelScope.launch {
            val collectionId = preferences.getChatCollectionId()
            val name = if (collectionId == null) "No docs" else collectionDao.getCollection(collectionId)?.name ?: "General"
            _uiState.update { it.copy(chatCollectionId = collectionId, chatCollectionName = name) }
        }
        viewModelScope.launch {
            preferences.enableThinking.collect { enabled ->
                _uiState.update { it.copy(enableThinking = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.userEmail.collect { email ->
                _uiState.update { it.copy(userEmail = email) }
            }
        }
        viewModelScope.launch { ensureDefaultSession() }
        viewModelScope.launch { checkCompliance() }
        checkModelLoaded()
    }

    // ── Session management ────────────────────────────────────────────────────

    private suspend fun checkCompliance() {
        val lastSeen = preferences.getComplianceLastSeen()
        val now = currentTimeMillis()
        val ninetyDaysMillis = 90L * 24L * 60L * 60L * 1000L
        if (now - lastSeen > ninetyDaysMillis) {
            _uiState.update { it.copy(showComplianceReminder = true) }
        }
    }

    fun acknowledgeCompliance() {
        viewModelScope.launch {
            preferences.setComplianceLastSeen(currentTimeMillis())
            _uiState.update { it.copy(showComplianceReminder = false) }
        }
    }

    fun submitReport(messageId: String, content: String, reason: String, email: String) {
        viewModelScope.launch {
            preferences.setUserEmail(email)
            val messages = _uiState.value.messages
            val index = messages.indexOfFirst { it.id == messageId }
            val query = if (index > 0) messages[index - 1].content else "N/A"
            val refId = messages.getOrNull(index)?.provenanceId ?: "N/A"

            reportingService.sendReport(
                messageId = refId,
                query = query,
                response = content,
                reason = reason,
                userEmail = email
            )
        }
    }

    private suspend fun ensureDefaultSession() {
        val sessions = chatSessionDao.getAllSessionsList()
        val activeId = preferences.activeSessionId.first()
        when {
            sessions.isEmpty() -> {
                val s = newSessionEntity("New Chat")
                chatSessionDao.insertSession(s)
                preferences.setActiveSessionId(s.id)
            }
            activeId.isEmpty() || sessions.none { it.id == activeId } ->
                preferences.setActiveSessionId(sessions.first().id)
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            val s = newSessionEntity("New Chat")
            chatSessionDao.insertSession(s)
            switchToSession(s.id)
            // Always start a new session with No docs selected
            preferences.setActiveCollectionId(null)
            _uiState.update { it.copy(chatCollectionId = null, chatCollectionName = "No docs") }
        }
    }

    fun switchToSession(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(messages = emptyList(), inputText = "") }
            preferences.setActiveSessionId(sessionId)
            val title = chatSessionDao.getSession(sessionId)?.title ?: "Chat"
            _uiState.update { it.copy(activeSessionId = sessionId, activeSessionTitle = title) }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatDao.deleteMessagesForSession(sessionId)
            chatSessionDao.deleteSession(sessionId)
            if (sessionId == currentSessionId) {
                val remaining = chatSessionDao.getAllSessionsList()
                if (remaining.isNotEmpty()) switchToSession(remaining.first().id) else createNewSession()
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            val s = chatSessionDao.getSession(sessionId) ?: return@launch
            chatSessionDao.updateSession(s.copy(title = newTitle.trim().take(60)))
            if (sessionId == currentSessionId) _uiState.update { it.copy(activeSessionTitle = newTitle.trim().take(60)) }
        }
    }

    fun regenerateMessage(messageId: String) {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            val msgs = uiState.value.messages
            val idx = msgs.indexOfFirst { it.id == messageId }
            if (idx == -1) return@launch
            val assistantMsg = msgs[idx]
            if (assistantMsg.role != "assistant") return@launch
            val userIdx = idx - 1
            if (userIdx < 0) return@launch
            val userMsg = msgs[userIdx]
            if (userMsg.role != "user") return@launch
            msgs.subList(userIdx, msgs.size).forEach { chatDao.deleteMessage(it.id) }
            _uiState.update { s -> s.copy(messages = s.messages.take(userIdx), pendingImagePath = userMsg.imagePath, pendingAudioPath = userMsg.audioPath) }
            sendMessage(userMsg.content)
        }
    }

    fun editAndResendMessage(userMessageId: String, newText: String) {
        if (newText.isBlank() || _uiState.value.isGenerating) return
        viewModelScope.launch {
            val msgs = uiState.value.messages
            val idx = msgs.indexOfFirst { it.id == userMessageId }
            if (idx == -1) return@launch
            val userMsg = msgs[idx]
            if (userMsg.role != "user") return@launch
            msgs.subList(idx, msgs.size).forEach { chatDao.deleteMessage(it.id) }
            _uiState.update { s -> s.copy(messages = s.messages.take(idx), pendingImagePath = userMsg.imagePath, pendingAudioPath = userMsg.audioPath) }
            sendMessage(newText.trim())
        }
    }

    /** Delete from this user message onwards and re-send the same content. */
    fun restartFromMessage(userMessageId: String) {
        val msg = _uiState.value.messages.find { it.id == userMessageId } ?: return
        if (msg.role != "user") return
        editAndResendMessage(userMessageId, msg.content)
    }

    fun selectChatCollection(id: String?) {
        viewModelScope.launch {
            val name = if (id == null) "No docs" else collectionDao.getCollection(id)?.name ?: "General"
            preferences.setActiveCollectionId(id)
            _uiState.update { it.copy(chatCollectionId = id, chatCollectionName = name) }
        }
    }

    fun clearAttachedImage() { _uiState.update { it.copy(pendingImagePath = null) } }
    fun clearAttachedAudio() { _uiState.update { it.copy(pendingAudioPath = null) } }

    fun setInputText(text: String) { _uiState.update { it.copy(inputText = text) } }
    fun stopGeneration() {
        generationJob?.cancel()
        inferenceService.stopGeneration()
    }
    fun toggleThinking() { viewModelScope.launch { preferences.setEnableThinking(!_uiState.value.enableThinking) } }

    fun refreshModelState() {
        val model = inferenceService.getCurrentModel()
        _uiState.update { it.copy(isModelLoaded = model != null, loadedModelName = model?.displayName ?: "") }
    }
    private fun checkModelLoaded() = refreshModelState()
    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }

    private suspend fun autoLoadModel(): Boolean {
        val modelId = preferences.selectedModelId.first()
        val platformModels = GemmaModels.forPlatform(isIosPlatform())
        val model = platformModels.find { it.id == modelId } ?: GemmaModels.defaultForPlatform(isIosPlatform())
        _uiState.update { it.copy(isAutoLoadingModel = true, autoLoadStatus = "Loading ${model.displayName}…") }
        val prefs = preferences
        inferenceService.setGenerationParams(
            topK = prefs.topK.first(),
            temperature = prefs.temperature.first(),
            enableThinking = prefs.enableThinking.first(),
            maxTokens = prefs.maxOutputTokens.first(),
            accelerator = prefs.accelerator.first()
        )
        val ok = inferenceService.loadModel(model)
        _uiState.update {
            if (ok) it.copy(isAutoLoadingModel = false, autoLoadStatus = "", isModelLoaded = true, loadedModelName = model.displayName)
            else it.copy(isAutoLoadingModel = false, autoLoadStatus = "", isModelLoaded = false, loadedModelName = "",
                errorMessage = "Could not load ${model.displayName}. Make sure ${model.fileName} has been downloaded in Settings.")
        }
        return ok
    }

    fun sendMessage(userText: String) {
        val hasAudio = _uiState.value.pendingAudioPath != null
        if ((userText.isBlank() && !hasAudio) || _uiState.value.isGenerating) return
        val sessionId = currentSessionId
        if (sessionId.isEmpty()) { _uiState.update { it.copy(errorMessage = "No active session.") }; return }

        val capturedImagePath = _uiState.value.pendingImagePath
        val capturedAudioPath = _uiState.value.pendingAudioPath
        _uiState.update { it.copy(pendingImagePath = null, pendingAudioPath = null) }

        generationJob = viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, inputText = "", errorMessage = null) }
            if (!inferenceService.isLoaded()) {
                val loaded = autoLoadModel()
                if (!loaded) { _uiState.update { it.copy(isGenerating = false) }; return@launch }
            }

            // Audio recording is v2 scope
            val audioBytes: ByteArray? = null
            val transcription: String? = null
            val effectiveUserText = userText

            val assistantMsgId = randomUUID()
            val fullResponse = StringBuilder()
            val fullThinking = StringBuilder()
            var agentStepsList = emptyList<AgentStep>()
            var sourcesList = emptyList<SourceChunk>()
            var messageSaved = false
            var wasCancelled = false
            var usedDirectMode = false

            try {
                // Start the foreground service inside try so finally always pairs with it
                inferenceService.startInferenceForeground()

                val userMsgId = randomUUID()
                chatDao.insertMessage(
                    ChatMessageEntity(
                        id = userMsgId, sessionId = sessionId, role = "user",
                        content = effectiveUserText, imagePath = capturedImagePath,
                        audioPath = capturedAudioPath, isTranscribed = false
                    )
                )
                autoTitleSession(sessionId, effectiveUserText)
                val history = buildHistory(sessionId)
                _uiState.update { it.copy(streamingMessage = ChatMessage(assistantMsgId, "assistant", "", isStreaming = true)) }

                val enableAgenticRag = preferences.enableAgenticRag.first()
                val maxChunks = preferences.maxRetrievalChunks.first()
                val enableSelfCritique = preferences.enableSelfCritique.first()
                val topK = preferences.topK.first()
                val temperature = preferences.temperature.first()
                val enableThinking = preferences.enableThinking.first()
                val accelerator = preferences.accelerator.first()
                val collectionId = _uiState.value.chatCollectionId
                inferenceService.setGenerationParams(
                    topK = topK, temperature = temperature, enableThinking = enableThinking,
                    maxTokens = preferences.maxOutputTokens.first(), accelerator = accelerator
                )

                val answerFlow = if (collectionId == null) {
                    usedDirectMode = true
                    val directPrompt = if (history.isNotEmpty()) "$history\nuser: $effectiveUserText" else effectiveUserText
                    inferenceService.generateStream(directPrompt, buildDirectSystemPrompt(), false, capturedImagePath, audioBytes)
                } else {
                    val result = orchestrator.process(
                        effectiveUserText, history, enableAgenticRag, maxChunks, enableSelfCritique,
                        true, collectionId, capturedImagePath, audioBytes,
                        onStep = { step ->
                            agentStepsList = agentStepsList + step
                            _uiState.update { s -> s.copy(streamingMessage = (s.streamingMessage ?: ChatMessage(assistantMsgId, "assistant", "", isStreaming = true)).copy(agentSteps = agentStepsList)) }
                        },
                        onSources = { chunks ->
                            sourcesList = chunks.map { SourceChunk(it.fileName, it.content) }
                            _uiState.update { s -> s.copy(streamingMessage = (s.streamingMessage ?: ChatMessage(assistantMsgId, "assistant", "", isStreaming = true)).copy(usedSources = sourcesList)) }
                        }
                    )
                    agentStepsList = result.steps
                    result.answerFlow
                }

                var thinkOpen = false
                answerFlow.collect { chunk ->
                    when (chunk) {
                        InferenceService.SENTINEL_THINK    -> { thinkOpen = true }
                        InferenceService.SENTINEL_ENDTHINK -> { thinkOpen = false }
                        else -> {
                            if (thinkOpen) {
                                fullThinking.append(chunk)
                                _uiState.update { s -> s.copy(streamingMessage = (s.streamingMessage ?: ChatMessage(assistantMsgId, "assistant", "", isStreaming = true)).copy(thinkingContent = fullThinking.toString(), isStreaming = true)) }
                            } else {
                                fullResponse.append(chunk)
                                _uiState.update { s -> s.copy(streamingMessage = (s.streamingMessage ?: ChatMessage(assistantMsgId, "assistant", "", isStreaming = true)).copy(content = fullResponse.toString(), isStreaming = true)) }
                            }
                        }
                    }
                }

                val finalContent = fullResponse.toString().trim()
                val finalThinking = fullThinking.toString().trim()
                chatDao.insertMessage(
                    ChatMessageEntity(
                        id = assistantMsgId, sessionId = sessionId, role = "assistant",
                        content = finalContent, agentSteps = serializeAgentSteps(agentStepsList),
                        thinkingContent = finalThinking, usedSources = serializeSources(sourcesList)
                    )
                )
                messageSaved = true
                val msgCount = chatDao.getMessagesForSessionList(sessionId).size
                chatSessionDao.getSession(sessionId)?.let { s ->
                    chatSessionDao.updateSession(s.copy(updatedAt = currentTimeMillis(), messageCount = msgCount))
                }
                val finalMsg = ChatMessage(
                    assistantMsgId, "assistant", finalContent, agentStepsList,
                    false, finalThinking, false, usedDirectMode, usedSources = sourcesList
                )
                _uiState.update { s ->
                    val already = s.messages.any { it.id == assistantMsgId }
                    s.copy(
                        messages = if (already) s.messages else s.messages + finalMsg,
                        streamingMessage = null,
                        isGenerating = false
                    )
                }

            } catch (e: CancellationException) {
                wasCancelled = true
                throw e
            } catch (e: Exception) {
                println("[ChatViewModel] Generation failed: ${e.message}")
                val errorContent = if (fullResponse.isNotEmpty()) fullResponse.toString().trim() else "⚠️ Error: ${e.message}"
                _uiState.update { s ->
                    val already = s.messages.any { it.id == assistantMsgId }
                    val errMsg = ChatMessage(assistantMsgId, "assistant", errorContent, isStreaming = false)
                    s.copy(
                        messages = if (already) s.messages else s.messages + errMsg,
                        streamingMessage = null,
                        isGenerating = false,
                        errorMessage = e.message
                    )
                }
                messageSaved = false
            } finally {
                withContext(NonCancellable) {
                    try { inferenceService.stopInferenceForeground() }
                    catch (e: Throwable) { println("[ChatViewModel] stopInferenceForeground failed: ${e.message}") }
                    try {
                        if (!messageSaved) {
                            val partialContent = fullResponse.toString().trim()
                            val partialThinking = fullThinking.toString().trim()
                            if (partialContent.isNotEmpty() || partialThinking.isNotEmpty()) {
                                chatDao.insertMessage(
                                    ChatMessageEntity(
                                        assistantMsgId, sessionId, "assistant", partialContent,
                                        serializeAgentSteps(agentStepsList), partialThinking,
                                        usedSources = serializeSources(sourcesList)
                                    )
                                )
                                _uiState.update { s ->
                                    val already = s.messages.any { it.id == assistantMsgId }
                                    val msg = ChatMessage(assistantMsgId, "assistant", partialContent, agentStepsList, false, partialThinking, wasCancelled, usedDirectMode, usedSources = sourcesList)
                                    s.copy(
                                        messages = if (already) s.messages else s.messages + msg,
                                        streamingMessage = null,
                                        isGenerating = false
                                    )
                                }
                            } else if (wasCancelled) {
                                val stopMsg = "⚠️ Generation stopped by user."
                                chatDao.insertMessage(
                                    ChatMessageEntity(
                                        assistantMsgId, sessionId, "assistant", stopMsg,
                                        serializeAgentSteps(agentStepsList), usedSources = serializeSources(sourcesList)
                                    )
                                )
                                _uiState.update { s ->
                                    val already = s.messages.any { it.id == assistantMsgId }
                                    val msg = ChatMessage(assistantMsgId, "assistant", stopMsg, agentStepsList, false, wasStopped = true, usedDirectMode = usedDirectMode, usedSources = sourcesList)
                                    s.copy(
                                        messages = if (already) s.messages else s.messages + msg,
                                        streamingMessage = null,
                                        isGenerating = false
                                    )
                                }
                            } else {
                                _uiState.update { s -> s.copy(streamingMessage = null, isGenerating = false) }
                            }
                        }
                    } catch (e: Throwable) {
                        println("[ChatViewModel] finally block recovery failed: ${e.message}")
                        _uiState.update { s -> s.copy(streamingMessage = null, isGenerating = false) }
                    }
                }
            }
        }
    }

    fun clearCurrentChat() {
        viewModelScope.launch {
            if (currentSessionId.isNotEmpty()) {
                chatDao.deleteMessagesForSession(currentSessionId)
                chatSessionDao.getSession(currentSessionId)?.let { s ->
                    chatSessionDao.updateSession(s.copy(messageCount = 0, updatedAt = currentTimeMillis()))
                }
            }
        }
    }

    private suspend fun buildHistory(sessionId: String): String {
        var messages = chatDao.getMessagesForSessionList(sessionId).takeLast(MAX_HISTORY_MESSAGES)
        val lastUserIdx = messages.indexOfLast { it.role == "user" }
        if (lastUserIdx != -1) messages = messages.toMutableList().also { it.removeAt(lastUserIdx) }
        return messages.joinToString("\n") { "${it.role}: ${it.content}" }
    }

    private suspend fun autoTitleSession(sessionId: String, firstMessage: String) {
        val s = chatSessionDao.getSession(sessionId) ?: return
        if (s.title == "New Chat" && s.messageCount == 0) {
            val autoTitle = firstMessage.take(45).let { if (it.length == 45) "$it…" else it }
            chatSessionDao.updateSession(s.copy(title = autoTitle))
            _uiState.update { it.copy(activeSessionTitle = autoTitle) }
        }
    }

    private fun newSessionEntity(title: String) =
        ChatSessionEntity(randomUUID(), title, currentTimeMillis(), currentTimeMillis())

    private fun serializeAgentSteps(steps: List<AgentStep>): String =
        steps.joinToString("|") { "${it.type}::${it.description}" }

    private fun parseAgentSteps(raw: String): List<AgentStep> {
        if (raw.isBlank()) return emptyList()
        return raw.split("|").mapNotNull { val p = it.split("::", limit = 2); if (p.size == 2) AgentStep(p[0], p[1]) else null }
    }

    private fun serializeSources(sources: List<SourceChunk>): String =
        if (sources.isEmpty()) "" else Json.encodeToString(sources)

    private fun parseSources(raw: String): List<SourceChunk> =
        if (raw.isBlank()) emptyList() else try { Json.decodeFromString(raw) } catch (_: Exception) { emptyList() }

    private fun buildDirectSystemPrompt(): String {
        val now = Instant.fromEpochMilliseconds(currentTimeMillis())
        val tz = TimeZone.currentSystemDefault()
        val localTime = now.toLocalDateTime(tz)
        val dayNames = listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")
        val dayName = dayNames.getOrElse(localTime.dayOfWeek.ordinal) { "" }
        val monthNames = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
        val monthName = monthNames.getOrElse(localTime.monthNumber - 1) { "${localTime.monthNumber}" }
        val hour12 = when {
            localTime.hour == 0  -> 12
            localTime.hour <= 12 -> localTime.hour
            else                 -> localTime.hour - 12
        }
        val amPm = if (localTime.hour < 12) "AM" else "PM"
        val timeString = "$dayName, ${localTime.dayOfMonth} $monthName ${localTime.year}, $hour12:${localTime.minute.toString().padStart(2,'0')} $amPm"
        val tzId = tz.id
        return """
You are Anvit, a helpful and concise AI assistant.
Current date and time: $timeString
Timezone (approximate region hint only): $tzId
IMPORTANT: Do NOT assert the user's exact city or location with confidence — the timezone only hints at a broad region. If your answer depends on a precise location (e.g. nearby restaurants, local laws, specific addresses), ask the user to share their exact location instead of guessing.

Answer the question directly and concisely. When asked about the current time or date, use the information provided above.
        """.trimIndent()
    }
}
