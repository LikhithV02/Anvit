package com.anvit.localai.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anvit.localai.AnvitApplication
import com.anvit.localai.data.models.EmbeddingModelInfo
import com.anvit.localai.data.models.EmbeddingModels
import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.data.models.GemmaModels
import com.anvit.localai.download.DownloadProgress
import com.anvit.localai.download.DownloadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

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
    // Download state
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    // HuggingFace token
    val huggingFaceToken: String = "",
    val hfTokenSaved: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AnvitApplication
    private val prefs = app.preferences
    private val downloadService = app.modelDownloadService

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val availableModels: List<GemmaModel> = GemmaModels.all
    val availableEmbeddingModels: List<EmbeddingModelInfo> = EmbeddingModels.all

    init {
        viewModelScope.launch {
            combine(
                prefs.selectedModelId,
                prefs.enableThinking,
                prefs.enableAgenticRag,
                prefs.temperature,
                prefs.topK
            ) { modelId, thinking, agentic, temp, topK ->
                _uiState.update { it.copy(
                    selectedModelId = modelId,
                    enableThinking = thinking,
                    enableAgenticRag = agentic,
                    temperature = temp,
                    topK = topK
                ) }
            }.collect()
        }
        viewModelScope.launch {
            combine(prefs.maxRetrievalChunks, prefs.enableSelfCritique, prefs.retrievalMode, prefs.maxOutputTokens) { chunks, critique, mode, maxTokens ->
                _uiState.update { it.copy(maxRetrievalChunks = chunks, enableSelfCritique = critique, retrievalMode = mode, maxOutputTokens = maxTokens) }
            }.collect()
        }
        viewModelScope.launch {
            prefs.accelerator.collect { acc ->
                _uiState.update { it.copy(accelerator = acc) }
            }
        }
        viewModelScope.launch {
            prefs.huggingFaceToken.collect { token ->
                _uiState.update { it.copy(huggingFaceToken = token) }
            }
        }

        // Observe download progress
        viewModelScope.launch {
            downloadService.downloads.collect { downloadsMap ->
                _uiState.update { it.copy(downloads = downloadsMap) }
                // Refresh model files list when a download completes
                if (downloadsMap.values.any { it.state == DownloadState.COMPLETED }) {
                    refreshModelFiles()
                }
            }
        }

        refreshModelFiles()
        refreshEmbeddingStatus()
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch { prefs.setSelectedModelId(modelId) }
    }

    fun loadSelectedModel() {
        viewModelScope.launch {
            val model = GemmaModels.all.find { it.id == _uiState.value.selectedModelId } ?: return@launch
            _uiState.update { it.copy(isLoadingModel = true, modelLoadError = null, modelLoadSuccess = null) }
            app.inferenceService.setGenerationParams(
                topK = _uiState.value.topK,
                topP = 0.95f,
                temperature = _uiState.value.temperature,
                enableThinking = _uiState.value.enableThinking,
                maxTokens = _uiState.value.maxOutputTokens,
                accelerator = _uiState.value.accelerator
            )
            val ok = app.inferenceService.loadModel(model)
            _uiState.update {
                if (ok) it.copy(isLoadingModel = false, modelLoadSuccess = "${model.displayName} loaded successfully")
                else it.copy(isLoadingModel = false, modelLoadError = "Failed to load ${model.displayName}.\nEnsure ${model.fileName} exists in the models directory.")
            }
        }
    }

    // ─── Download management ───────────────────────────────────────────────────

    fun downloadGemmaModel(model: GemmaModel) {
        downloadService.startDownload(
            modelId = model.id,
            fileName = model.fileName,
            downloadUrl = model.downloadUrl,
            totalSizeBytes = model.sizeBytes,
            authToken = _uiState.value.huggingFaceToken
        )
    }

    fun downloadEmbeddingModel(model: EmbeddingModelInfo) {
        downloadService.startDownload(
            modelId = model.id,
            fileName = model.fileName,
            downloadUrl = model.downloadUrl,
            totalSizeBytes = model.sizeBytes,
            authToken = _uiState.value.huggingFaceToken
        )
    }

    fun setHuggingFaceToken(token: String) {
        _uiState.update { it.copy(huggingFaceToken = token, hfTokenSaved = false) }
    }

    fun saveHuggingFaceToken() {
        viewModelScope.launch {
            prefs.setHuggingFaceToken(_uiState.value.huggingFaceToken.trim())
            _uiState.update { it.copy(hfTokenSaved = true) }
            delay(2000)
            _uiState.update { it.copy(hfTokenSaved = false) }
        }
    }

    fun cancelDownload(modelId: String) {
        downloadService.cancelDownload(modelId)
    }

    fun deleteModel(fileName: String, modelId: String) {
        downloadService.deleteModel(fileName)
        downloadService.clearDownloadState(modelId)
        refreshModelFiles()
    }

    fun isModelFilePresent(fileName: String): Boolean =
        downloadService.isModelPresent(fileName)

    fun formatBytes(bytes: Long): String = downloadService.formatBytes(bytes)

    // ─── Settings ─────────────────────────────────────────────────────────────

    fun setEnableThinking(enabled: Boolean) {
        viewModelScope.launch { prefs.setEnableThinking(enabled) }
    }

    fun setEnableAgenticRag(enabled: Boolean) {
        viewModelScope.launch { prefs.setEnableAgenticRag(enabled) }
    }

    fun setTemperature(value: Float) {
        viewModelScope.launch { prefs.setTemperature(value) }
    }

    fun setTopK(value: Int) {
        viewModelScope.launch { prefs.setTopK(value) }
    }

    fun setMaxOutputTokens(value: Int) {
        viewModelScope.launch { prefs.setMaxOutputTokens(value) }
    }

    fun setAccelerator(value: String) {
        viewModelScope.launch { prefs.setAccelerator(value) }
    }

    fun setMaxRetrievalChunks(value: Int) {
        viewModelScope.launch { prefs.setMaxRetrievalChunks(value) }
    }

    fun setEnableSelfCritique(enabled: Boolean) {
        viewModelScope.launch { prefs.setEnableSelfCritique(enabled) }
    }

    fun setRetrievalMode(mode: String) {
        viewModelScope.launch { prefs.setRetrievalMode(mode) }
    }

    fun resetGenerationDefaults() {
        viewModelScope.launch {
            prefs.setTemperature(1.0f)
            prefs.setTopK(40)
            prefs.setMaxOutputTokens(4000)
            prefs.setAccelerator("cpu")
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(modelLoadError = null, modelLoadSuccess = null) }
    }

    fun refreshModelFiles() {
        val modelsDir = File(app.filesDir, "models")
        val files = if (modelsDir.exists()) modelsDir.listFiles()
            ?.filter { !it.name.endsWith(".part") }
            ?.map { it.name } ?: emptyList()
        else emptyList()
        _uiState.update { it.copy(availableModelFiles = files) }
    }

    private fun refreshEmbeddingStatus() {
        val status = if (app.embeddingService.isInitialized()) "Ready (${app.embeddingService.getModelName()})" else "Not initialized"
        _uiState.update { it.copy(embeddingModelStatus = status) }
    }

    fun initializeEmbedding() {
        viewModelScope.launch {
            _uiState.update { it.copy(embeddingModelStatus = "Initializing...") }
            val ok = app.embeddingService.initialize()
            _uiState.update { it.copy(embeddingModelStatus = if (ok) "Ready (${app.embeddingService.getModelName()})" else "Failed - model not found") }
        }
    }
}
