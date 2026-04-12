package com.sage.localai.download

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class DownloadProgress(
    val modelId: String,
    val state: DownloadState,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes.toFloat() else 0f
    val progressPercent: Int
        get() = (progressFraction * 100).toInt()
    val isActive: Boolean
        get() = state == DownloadState.DOWNLOADING
}

enum class DownloadState { IDLE, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

class ModelDownloadService(private val context: Context) {

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private val activeJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    fun isModelPresent(fileName: String): Boolean =
        File(modelsDir, fileName).exists()

    fun getDownloadProgress(modelId: String): DownloadProgress? =
        _downloads.value[modelId]

    fun startDownload(
        modelId: String,
        fileName: String,
        downloadUrl: String,
        totalSizeBytes: Long,
        authToken: String = ""
    ) {
        // Cancel existing download for this model if any
        activeJobs[modelId]?.cancel()

        val job = scope.launch {
            updateProgress(modelId, DownloadProgress(modelId, DownloadState.DOWNLOADING, totalBytes = totalSizeBytes))

            val destFile = File(modelsDir, "$fileName.part")
            val finalFile = File(modelsDir, fileName)

            // Resume support: check if partial file exists
            val bytesAlreadyDownloaded = if (destFile.exists()) destFile.length() else 0L

            var connection: HttpURLConnection? = null
            try {
                var redirectUrl = downloadUrl
                var redirectCount = 0

                // Follow redirects manually (HuggingFace does 302 redirects)
                while (redirectCount < 10) {
                    connection = URL(redirectUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 60_000
                    connection.setRequestProperty("User-Agent", "SageApp/1.0")
                    connection.setRequestProperty("Accept", "application/octet-stream")
                    if (authToken.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer $authToken")
                    }

                    if (bytesAlreadyDownloaded > 0 && redirectCount == 0) {
                        connection.setRequestProperty("Range", "bytes=$bytesAlreadyDownloaded-")
                    }

                    connection.instanceFollowRedirects = false
                    connection.connect()

                    val responseCode = connection.responseCode
                    if (responseCode in 301..308) {
                        val location = connection.getHeaderField("Location") ?: break
                        connection.disconnect()
                        redirectUrl = location
                        redirectCount++
                        continue
                    }
                    break
                }

                connection ?: throw Exception("Failed to establish connection")
                val responseCode = connection.responseCode

                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    throw Exception("HTTP $responseCode: This model is gated — enter a HuggingFace token in Settings")
                }
                if (responseCode != HttpURLConnection.HTTP_OK &&
                    responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    throw Exception("HTTP $responseCode: ${connection.responseMessage}")
                }

                val isPartialContent = responseCode == HttpURLConnection.HTTP_PARTIAL
                val contentLength = connection.contentLengthLong
                val effectiveTotalBytes = when {
                    totalSizeBytes > 0 -> totalSizeBytes
                    isPartialContent -> bytesAlreadyDownloaded + contentLength
                    contentLength > 0 -> contentLength
                    else -> 0L
                }

                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(destFile, isPartialContent)
                val buffer = ByteArray(128 * 1024) // 128 KB buffer
                var bytesRead: Int
                var totalDownloaded = if (isPartialContent) bytesAlreadyDownloaded else 0L
                var lastProgressUpdate = 0L

                try {
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        outputStream.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead

                        // Throttle UI updates to every 500ms
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 500) {
                            updateProgress(modelId, DownloadProgress(
                                modelId = modelId,
                                state = DownloadState.DOWNLOADING,
                                bytesDownloaded = totalDownloaded,
                                totalBytes = effectiveTotalBytes
                            ))
                            lastProgressUpdate = now
                        }
                    }
                } finally {
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                }

                // Rename .part → final file
                destFile.renameTo(finalFile)

                updateProgress(modelId, DownloadProgress(
                    modelId = modelId,
                    state = DownloadState.COMPLETED,
                    bytesDownloaded = totalDownloaded,
                    totalBytes = effectiveTotalBytes
                ))

            } catch (e: CancellationException) {
                updateProgress(modelId, DownloadProgress(modelId, DownloadState.CANCELLED))
                // Keep .part file for resuming later
            } catch (e: Exception) {
                destFile.delete()
                updateProgress(modelId, DownloadProgress(
                    modelId = modelId,
                    state = DownloadState.FAILED,
                    errorMessage = e.message ?: "Download failed"
                ))
            } finally {
                connection?.disconnect()
                activeJobs.remove(modelId)
            }
        }

        activeJobs[modelId] = job
    }

    fun cancelDownload(modelId: String) {
        activeJobs[modelId]?.cancel()
        activeJobs.remove(modelId)
        updateProgress(modelId, DownloadProgress(modelId, DownloadState.CANCELLED))
    }

    fun deleteModel(fileName: String) {
        File(modelsDir, fileName).delete()
        File(modelsDir, "$fileName.part").delete()
    }

    fun clearDownloadState(modelId: String) {
        _downloads.update { it - modelId }
    }

    private fun updateProgress(modelId: String, progress: DownloadProgress) {
        _downloads.update { current -> current + (modelId to progress) }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1_024 -> "${"%.1f".format(bytes / 1_024.0)} KB"
        else -> "$bytes B"
    }
}
