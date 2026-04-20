package com.anvit.localai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvit.localai.data.models.EmbeddingModelInfo
import com.anvit.localai.data.models.EmbeddingModels
import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.data.models.GemmaModels
import com.anvit.localai.data.preferences.AnvitPreferences
import com.anvit.localai.download.DownloadProgress
import com.anvit.localai.download.DownloadService
import com.anvit.localai.embedding.EmbeddingService
import com.anvit.localai.inference.InferenceService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val selectedModelId: String = "gemma4-e2b",
    val enableThinking: Boolean = true,
    val enableAgenticRag: Boolean = true,
    val temperature: Float = 1.0f,
    val topK: Int = 40,
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
    val huggingFaceToken: String = "",
    val hfTokenSaved: Boolean = false
)

class SettingsViewModel(
    private val preferences: AnvitPreferences,
    private val inferenceService: InferenceService,
    private val downloadService: DownloadService
) : ViewModel() {
    // EmbeddingService is accessed from Koin lazily to avoid circular dep
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val availableModels: List<GemmaModel> = GemmaModels.all
    val availableEmbeddingModels: List<EmbeddingModelInfo> = EmbeddingModels.all

    init {
        viewModelScope.launch {
            combine(preferences.selectedModelId, preferences.enableThinking, preferences.enableAgenticRag, preferences.temperature, preferences.topK) { mId, think, agentic, temp, topK ->
                _uiState.update { it.copy(selectedModelId = mId, enableThinking = think, enableAgenticRag = agentic, temperature = temp, topK = topK) }
            }.collect()
        }
        viewModelScope.launch {
            combine(preferences.maxRetrievalChunks, preferences.enableSelfCritique, preferences.retrievalMode, preferences.maxOutputTokens) { chunks, crit, mode, tok ->
                _uiState.update { it.copy(maxRetrievalChunks = chunks, enableSelfCritique = crit, retrievalMode = mode, maxOutputTokens = tok) }
            }.collect()
        }
        viewModelScope.launch { preferences.accelerator.collect { _uiState.update { s -> s.copy(accelerator = it) } } }
        viewModelScope.launch { preferences.huggingFaceToken.collect { t -> _uiState.update { s -> s.copy(huggingFaceToken = t) } } }
        viewModelScope.launch {
            downloadService.downloads.collect { map ->
                _uiState.update { it.copy(downloads = map) }
                if (map.values.any { it.state == com.anvit.localai.download.DownloadState.COMPLETED }) refreshModelFiles()
            }
        }
        refreshModelFiles()
    }

    fun selectModel(modelId: String) { viewModelScope.launch { preferences.setSelectedModelId(modelId) } }
    fun loadSelectedModel() {
        viewModelScope.launch {
            val model = GemmaModels.all.find { it.id == _uiState.value.selectedModelId } ?: return@launch
            _uiState.update { it.copy(isLoadingModel = true, modelLoadError = null, modelLoadSuccess = null) }
            inferenceService.setGenerationParams(
                topK = _uiState.value.topK, temperature = _uiState.value.temperature,
                enableThinking = _uiState.value.enableThinking, maxTokens = _uiState.value.maxOutputTokens,
                accelerator = _uiState.value.accelerator
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
    fun saveHuggingFaceToken() {
        viewModelScope.launch {
            preferences.setHuggingFaceToken(_uiState.value.huggingFaceToken.trim())
            _uiState.update { it.copy(hfTokenSaved = true) }
            delay(2000)
            _uiState.update { it.copy(hfTokenSaved = false) }
        }
    }
    fun cancelDownload(modelId: String) { downloadService.cancelDownload(modelId) }
    fun deleteModel(fileName: String, modelId: String) {
        viewModelScope.launch {
            if (inferenceService.getCurrentModel()?.id == modelId) {
                inferenceService.unloadModel()
            }
            downloadService.deleteModel(fileName)
            downloadService.clearDownloadState(modelId)
            refreshModelFiles()
        }
    }
    fun isModelFilePresent(fileName: String): Boolean = downloadService.isModelPresent(fileName)
    fun formatBytes(bytes: Long): String = downloadService.formatBytes(bytes)
    fun setEnableThinking(v: Boolean) { viewModelScope.launch { preferences.setEnableThinking(v) } }
    fun setEnableAgenticRag(v: Boolean) { viewModelScope.launch { preferences.setEnableAgenticRag(v) } }
    fun setTemperature(v: Float) { viewModelScope.launch { preferences.setTemperature(v) } }
    fun setTopK(v: Int) { viewModelScope.launch { preferences.setTopK(v) } }
    fun setMaxOutputTokens(v: Int) { viewModelScope.launch { preferences.setMaxOutputTokens(v) } }
    fun setAccelerator(v: String) { viewModelScope.launch { preferences.setAccelerator(v) } }
    fun setMaxRetrievalChunks(v: Int) { viewModelScope.launch { preferences.setMaxRetrievalChunks(v) } }
    fun setEnableSelfCritique(v: Boolean) { viewModelScope.launch { preferences.setEnableSelfCritique(v) } }
    fun setRetrievalMode(m: String) { viewModelScope.launch { preferences.setRetrievalMode(m) } }
    fun resetGenerationDefaults() {
        viewModelScope.launch {
            preferences.setTemperature(1.0f)
            preferences.setTopK(40)
            preferences.setMaxOutputTokens(4000)
            preferences.setAccelerator("cpu")
        }
    }
    fun clearMessages() { _uiState.update { it.copy(modelLoadError = null, modelLoadSuccess = null) } }
    fun refreshModelFiles() { /* Platform-specific file listing — overridden via platform VM or expect */ }
    fun initializeEmbedding() { /* Handled via platform Koin module injection */ }
}
