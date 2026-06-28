package com.anvit.localai.download

import kotlinx.coroutines.flow.StateFlow

data class DownloadProgress(
    val modelId: String,
    val state: DownloadState,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val downloadSpeed: String? = null,
    val errorMessage: String? = null
) {
    val progressFraction: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    val progressPercent: Int    get() = (progressFraction * 100).toInt()
    val isActive: Boolean       get() = state == DownloadState.DOWNLOADING
}

enum class DownloadState { IDLE, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

interface DownloadService {
    val downloads: StateFlow<Map<String, DownloadProgress>>
    fun startDownload(
        modelId: String,
        fileName: String,
        downloadUrl: String,
        totalSizeBytes: Long,
        authToken: String = "",
        archiveFileName: String? = null
    )
    fun pauseDownload(modelId: String)
    fun cancelDownload(modelId: String)
    fun isModelPresent(fileName: String): Boolean
    fun deleteModel(fileName: String)
    fun clearDownloadState(modelId: String)
    fun formatBytes(bytes: Long): String
}
