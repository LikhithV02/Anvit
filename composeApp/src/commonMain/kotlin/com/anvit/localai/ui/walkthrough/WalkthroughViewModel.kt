package com.anvit.localai.ui.walkthrough

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvit.localai.data.models.EmbeddingModels
import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.data.models.GemmaModels
import com.anvit.localai.data.preferences.AnvitPreferences
import com.anvit.localai.device.DeviceRecommendation
import com.anvit.localai.device.getDeviceRecommendation
import com.anvit.localai.download.DownloadProgress
import com.anvit.localai.download.DownloadService
import com.anvit.localai.download.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WalkthroughUiState(
    val showSheet: Boolean = false,
    val currentStep: Int = 0,
    val recommendation: DeviceRecommendation? = null,
    val llmModelOptions: List<GemmaModel> = emptyList(),
    val selectedLlmModelId: String? = null,
    val llmDownloadProgressById: Map<String, DownloadProgress> = emptyMap(),
    val geckoDownloadProgress: DownloadProgress? = null,
    val llmAlreadyPresentById: Map<String, Boolean> = emptyMap(),
    val geckoAlreadyPresent: Boolean = false,
)

// Routes corresponding to each step index (0–5)
val walkthroughStepRoutes = listOf("chat", "settings", "settings", "documents", "documents", "chat")

private val GECKO = EmbeddingModels.GECKO_512

private fun llmModelsForRecommendation(rec: DeviceRecommendation): List<GemmaModel> {
    val recommendedModel = GemmaModels.all.find { it.id == rec.modelId }
    val platformModels = recommendedModel
        ?.let { model -> GemmaModels.all.filter { it.platform == model.platform } }
        ?: GemmaModels.forPlatform(ios = false)
    return platformModels.sortedBy { it.sizeBytes }
}

class WalkthroughViewModel(
    private val preferences: AnvitPreferences,
    private val downloadService: DownloadService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WalkthroughUiState())
    val uiState: StateFlow<WalkthroughUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val seen = preferences.walkthroughSeen.first()
            if (seen) return@launch

            val rec = getDeviceRecommendation()
            val llmModels = llmModelsForRecommendation(rec)
            val llmPresentById = llmModels.associate { model ->
                model.id to downloadService.isModelPresent(model.fileName)
            }
            val geckoPresent = downloadService.isModelPresent(GECKO.fileName)
            val initialSelectedModelId = when {
                llmPresentById[rec.modelId] == true -> rec.modelId
                else -> llmPresentById.entries.firstOrNull { it.value }?.key ?: rec.modelId
            }

            _uiState.update {
                it.copy(
                    showSheet = true,
                    recommendation = rec,
                    llmModelOptions = llmModels,
                    selectedLlmModelId = initialSelectedModelId,
                    llmAlreadyPresentById = llmPresentById,
                    geckoAlreadyPresent = geckoPresent,
                )
            }

            // Auto-advance past download steps if files already present
            var startStep = 0
            if (startStep == 0) {
                // Welcome step — don't auto-advance, user must tap Next
            }
        }

        // Observe download progress
        viewModelScope.launch {
            downloadService.downloads.collect { downloads ->
                val rec = _uiState.value.recommendation ?: return@collect
                val llmModels = _uiState.value.llmModelOptions.ifEmpty {
                    llmModelsForRecommendation(rec)
                }

                val llmProgressById = llmModels.mapNotNull { model ->
                    downloads[model.id]?.let { progress -> model.id to progress }
                }.toMap()
                val geckoProgress = downloads[GECKO.id]

                _uiState.update { state ->
                    state.copy(
                        llmModelOptions = llmModels,
                        llmDownloadProgressById = llmProgressById,
                        geckoDownloadProgress = geckoProgress,
                        llmAlreadyPresentById = llmModels.associate { model ->
                            val currentPresent = state.llmAlreadyPresentById[model.id] == true
                            val completed = llmProgressById[model.id]?.state == DownloadState.COMPLETED
                            model.id to (currentPresent || completed || downloadService.isModelPresent(model.fileName))
                        },
                        geckoAlreadyPresent = state.geckoAlreadyPresent ||
                            geckoProgress?.state == DownloadState.COMPLETED ||
                            downloadService.isModelPresent(GECKO.fileName),
                    )
                }
            }
        }
    }

    fun canAdvance(step: Int): Boolean {
        val state = _uiState.value
        return when (step) {
            1 -> state.llmModelOptions.any { model ->
                state.llmAlreadyPresentById[model.id] == true ||
                    state.llmDownloadProgressById[model.id]?.state == DownloadState.COMPLETED
            }
            2 -> state.geckoAlreadyPresent || state.geckoDownloadProgress?.state == DownloadState.COMPLETED
            else -> true
        }
    }

    fun nextStep() {
        val state = _uiState.value
        if (!canAdvance(state.currentStep)) return

        val next = state.currentStep + 1
        if (next >= walkthroughStepRoutes.size) {
            complete()
            return
        }

        var finalStep = next
        // Auto-advance past LLM step if already present
        if (finalStep == 1 && state.llmAlreadyPresentById.values.any { it }) finalStep = 2
        // Auto-advance past Gecko step if already present
        if (finalStep == 2 && state.geckoAlreadyPresent) finalStep = 3

        if (finalStep >= walkthroughStepRoutes.size) {
            complete()
        } else {
            _uiState.update { it.copy(currentStep = finalStep) }
        }
    }

    fun skip() = complete()

    fun selectLlmModel(modelId: String) {
        _uiState.update { it.copy(selectedLlmModelId = modelId) }
    }

    fun startLlmDownload(modelId: String) {
        val model = _uiState.value.llmModelOptions.find { it.id == modelId }
            ?: GemmaModels.all.find { it.id == modelId }
            ?: GemmaModels.E2B
        selectLlmModel(model.id)
        downloadService.startDownload(
            modelId       = model.id,
            fileName      = model.fileName,
            downloadUrl   = model.downloadUrl,
            totalSizeBytes = model.sizeBytes,
        )
    }

    fun startGeckoDownload() {
        downloadService.startDownload(
            modelId        = GECKO.id,
            fileName       = GECKO.fileName,
            downloadUrl    = GECKO.downloadUrl,
            totalSizeBytes = GECKO.sizeBytes,
        )
    }

    private fun complete() {
        viewModelScope.launch {
            _uiState.value.recommendation?.let { rec ->
                val selectedModelId = _uiState.value.selectedLlmModelId ?: rec.modelId
                preferences.setSelectedModelId(selectedModelId)
                preferences.setAccelerator(if (selectedModelId == rec.modelId) rec.accelerator else "cpu")
            }
            preferences.setWalkthroughSeen(true)
            _uiState.update { it.copy(showSheet = false) }
        }
    }
}
