package com.anvit.localai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvit.localai.data.models.EmbeddingModelInfo
import com.anvit.localai.data.models.EmbeddingModels
import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.data.models.GemmaModels
import com.anvit.localai.utils.isIosPlatform
import com.anvit.localai.data.preferences.AnvitPreferences
import com.anvit.localai.download.DownloadProgress
import com.anvit.localai.download.DownloadService
import com.anvit.localai.embedding.EmbeddingService
import com.anvit.localai.inference.InferenceService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val selectedModelId: String = GemmaModels.defaultForPlatform(isIosPlatform()).id,
    val enableThinking: Boolean = true,
    val enableAgenticRag: Boolean = true,
    val temperature: Float = 1.0f,
    val topK: Int = 40,
    val contextWindow: Int = 8192,
    val maxOutputTokens: Int = 4000,
    val accelerator: String = "cpu",
    val maxRetrievalChunks: Int = 5,
    val enableSelfCritique: Boolean = true,
    val retrievalMode: String = "hybrid",
    val isLoadingModel: Boolean = false,
    val modelLoadError: String? = null,
    val modelLoadSuccess: String? = null,
    val availableModelFiles: List<String> = emptyList(),
    val embeddingModelStatus: String = "Not initialized",
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val deletionTick: Int = 0,
    val huggingFaceToken: String = "",
    val hfTokenSaved: Boolean = false,
    val userEmail: String = "",
    val themeMode: String = "system",
)

class SettingsViewModel(
    private val preferences: AnvitPreferences,
    private val inferenceService: InferenceService,
    private val downloadService: DownloadService,
    private val embeddingService: EmbeddingService
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val availableModels: List<GemmaModel> = GemmaModels.forPlatform(isIosPlatform())
    val availableEmbeddingModels: List<EmbeddingModelInfo> = EmbeddingModels.all

    init {
        viewModelScope.launch {
            combine(preferences.selectedModelId, preferences.enableThinking, preferences.enableAgenticRag, preferences.temperature, preferences.topK) { mId, think, agentic, temp, topK ->
                _uiState.update { it.copy(selectedModelId = mId, enableThinking = think, enableAgenticRag = agentic, temperature = temp, topK = topK) }
            }.collect()
        }
        viewModelScope.launch {
            combine(preferences.maxRetrievalChunks, preferences.enableSelfCritique, preferences.retrievalMode, preferences.maxOutputTokens, preferences.contextWindow) { chunks, crit, mode, tok, window ->
                _uiState.update { it.copy(maxRetrievalChunks = chunks, enableSelfCritique = crit, retrievalMode = mode, maxOutputTokens = tok, contextWindow = window) }
            }.collect()
        }
        viewModelScope.launch { preferences.accelerator.collect { _uiState.update { s -> s.copy(accelerator = it) } } }
        viewModelScope.launch { preferences.themeMode.collect { m -> _uiState.update { s -> s.copy(themeMode = m) } } }
        viewModelScope.launch { preferences.huggingFaceToken.collect { t -> _uiState.update { s -> s.copy(huggingFaceToken = t) } } }
        viewModelScope.launch {
            val initialEmail = preferences.userEmail.first()
            _uiState.update { s -> s.copy(userEmail = initialEmail) }
        }
        viewModelScope.launch {
            downloadService.downloads.collect { map ->
                _uiState.update { it.copy(downloads = map) }
                if (map.values.any { it.state == com.anvit.localai.download.DownloadState.COMPLETED }) {
                    refreshModelFiles()
                    checkAndPromote4BDefault()
                }
            }
        }
        refreshModelFiles()
        checkAndPromote4BDefault()
    }

    private fun checkAndPromote4BDefault() {
        val models = GemmaModels.forPlatform(isIosPlatform())
        val allPresent = models.all { downloadService.isModelPresent(it.fileName) }
        if (!allPresent) return
        val fourB = models.maxByOrNull { it.sizeBytes } ?: return
        viewModelScope.launch {
            val current = preferences.selectedModelId.first()
            if (current != fourB.id) preferences.setSelectedModelId(fourB.id)
        }
    }

    fun selectModel(modelId: String) { viewModelScope.launch { preferences.setSelectedModelId(modelId) } }
    fun loadSelectedModel() {
        viewModelScope.launch {
            val model = availableModels.find { it.id == _uiState.value.selectedModelId } ?: return@launch
            if (!downloadService.isModelPresent(model.fileName)) {
                _uiState.update {
                    it.copy(modelLoadError = "${model.displayName} is not downloaded yet.")
                }
                return@launch
            }
            _uiState.update { it.copy(isLoadingModel = true, modelLoadError = null, modelLoadSuccess = null) }
            val safeAccelerator = if (!isIosPlatform() && model.id == GemmaModels.E2B.id) "cpu" else _uiState.value.accelerator
            if (safeAccelerator != _uiState.value.accelerator) {
                preferences.setAccelerator(safeAccelerator)
                _uiState.update { it.copy(accelerator = safeAccelerator) }
            }
            inferenceService.setGenerationParams(
                topK = _uiState.value.topK, temperature = _uiState.value.temperature,
                enableThinking = _uiState.value.enableThinking, maxTokens = _uiState.value.maxOutputTokens,
                contextWindow = _uiState.value.contextWindow,
                accelerator = safeAccelerator
            )
            val ok = inferenceService.loadModel(model)
            _uiState.update {
                if (ok) it.copy(isLoadingModel = false, modelLoadSuccess = "${model.displayName} loaded successfully")
                else it.copy(isLoadingModel = false, modelLoadError = "Failed to load ${model.displayName}.")
            }
        }
    }
    fun downloadGemmaModel(model: GemmaModel) {
        downloadService.startDownload(model.id, model.fileName, model.downloadUrl, model.sizeBytes, _uiState.value.huggingFaceToken)
    }
    fun downloadEmbeddingModel(model: EmbeddingModelInfo) {
        downloadService.startDownload(model.id, model.fileName, model.downloadUrl, model.sizeBytes, _uiState.value.huggingFaceToken)
    }
    fun setHuggingFaceToken(t: String) { _uiState.update { it.copy(huggingFaceToken = t, hfTokenSaved = false) } }
    fun setUserEmail(e: String) {
        _uiState.update { it.copy(userEmail = e) }
        viewModelScope.launch { preferences.setUserEmail(e) }
    }
    fun saveHuggingFaceToken() {
        viewModelScope.launch {
            preferences.setHuggingFaceToken(_uiState.value.huggingFaceToken.trim())
            _uiState.update { it.copy(hfTokenSaved = true) }
            delay(2000)
            _uiState.update { it.copy(hfTokenSaved = false) }
        }
    }
    fun pauseDownload(modelId: String)  { downloadService.pauseDownload(modelId) }
    fun cancelDownload(modelId: String) { downloadService.cancelDownload(modelId) }
    fun deleteModel(fileName: String, modelId: String) {
        viewModelScope.launch {
            if (inferenceService.getCurrentModel()?.id == modelId) {
                inferenceService.unloadModel()
            }
            downloadService.deleteModel(fileName)
            downloadService.clearDownloadState(modelId)
            if (isEmbeddingModel(modelId)) {
                embeddingService.cleanup()
            }
            _uiState.update { it.copy(deletionTick = it.deletionTick + 1) }
            refreshModelFiles()
        }
    }
    fun isModelFilePresent(fileName: String): Boolean = downloadService.isModelPresent(fileName)
    fun formatBytes(bytes: Long): String = downloadService.formatBytes(bytes)
    fun setEnableThinking(v: Boolean) { viewModelScope.launch { preferences.setEnableThinking(v) } }
    fun setEnableAgenticRag(v: Boolean) { viewModelScope.launch { preferences.setEnableAgenticRag(v) } }
    fun setTemperature(v: Float) { viewModelScope.launch { preferences.setTemperature(v) } }
    fun setTopK(v: Int) { viewModelScope.launch { preferences.setTopK(v) } }
    fun setContextWindow(v: Int) { viewModelScope.launch { preferences.setContextWindow(v) } }
    fun setMaxOutputTokens(v: Int) { viewModelScope.launch { preferences.setMaxOutputTokens(v) } }
    fun setAccelerator(v: String) {
        _uiState.update { it.copy(accelerator = v) }
        viewModelScope.launch { preferences.setAccelerator(v) }
    }
    fun setMaxRetrievalChunks(v: Int) { viewModelScope.launch { preferences.setMaxRetrievalChunks(v) } }
    fun setEnableSelfCritique(v: Boolean) { viewModelScope.launch { preferences.setEnableSelfCritique(v) } }
    fun setRetrievalMode(m: String) { viewModelScope.launch { preferences.setRetrievalMode(m) } }
    fun setThemeMode(mode: String)  { viewModelScope.launch { preferences.setThemeMode(mode) } }
    fun resetGenerationDefaults() {
        viewModelScope.launch {
            preferences.setTemperature(1.0f)
            preferences.setTopK(40)
            preferences.setContextWindow(8192)
            preferences.setMaxOutputTokens(4000)
        }
    }
    fun clearMessages() { _uiState.update { it.copy(modelLoadError = null, modelLoadSuccess = null) } }
    fun refreshModelFiles() {
        _uiState.update { it.copy(embeddingModelStatus = currentEmbeddingStatus()) }
    }
    fun initializeEmbedding() {
        viewModelScope.launch {
            if (!hasDownloadedEmbeddingModel()) {
                embeddingService.cleanup()
                _uiState.update { it.copy(embeddingModelStatus = "Download a model above to enable document search") }
                return@launch
            }

            _uiState.update { it.copy(embeddingModelStatus = "Activating…") }
            val ok = embeddingService.initialize()
            _uiState.update {
                it.copy(embeddingModelStatus = if (ok) "Active" else "Activation failed — try again")
            }
        }
    }

    private fun isEmbeddingModel(modelId: String): Boolean =
        availableEmbeddingModels.any { it.id == modelId }

    private fun hasDownloadedEmbeddingModel(): Boolean =
        availableEmbeddingModels.any { downloadService.isModelPresent(it.fileName) }

    private fun currentEmbeddingStatus(): String = when {
        embeddingService.isInitialized() -> "Active"
        hasDownloadedEmbeddingModel() -> "Ready to activate"
        else -> "Download a model above to enable document search"
    }
}
