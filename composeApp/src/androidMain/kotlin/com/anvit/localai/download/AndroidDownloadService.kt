package com.anvit.localai.download

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android implementation of [DownloadService] using HttpURLConnection.
 * Supports resume-on-retry via HTTP Range requests.
 */
class AndroidDownloadService(private val context: Context) : DownloadService {

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    override val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private val activeJobs = mutableMapOf<String, Job>()
    private val activeFileNames = mutableMapOf<String, String>()
    private val pausingModels = mutableSetOf<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    override fun isModelPresent(fileName: String): Boolean =
        File(modelsDir, fileName).exists()

    override fun startDownload(
        modelId: String,
        fileName: String,
        downloadUrl: String,
        totalSizeBytes: Long,
        authToken: String
    ) {
        activeJobs[modelId]?.cancel()
        activeFileNames[modelId] = fileName

        val job = scope.launch {
            updateProgress(modelId, DownloadProgress(modelId, DownloadState.DOWNLOADING, totalBytes = totalSizeBytes))

            val destFile  = File(modelsDir, "$fileName.part")
            val finalFile = File(modelsDir, fileName)

            val bytesAlreadyDownloaded = if (destFile.exists()) destFile.length() else 0L
            var connection: HttpURLConnection? = null

            try {
                var redirectUrl   = downloadUrl
                var redirectCount = 0

                while (redirectCount < 10) {
                    connection = URL(redirectUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 30_000
                    connection.readTimeout    = 60_000
                    connection.setRequestProperty("User-Agent", "AnvitApp/1.0")
                    connection.setRequestProperty("Accept", "application/octet-stream")
                    if (authToken.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer $authToken")
                    }
                    if (bytesAlreadyDownloaded > 0 && redirectCount == 0) {
                        connection.setRequestProperty("Range", "bytes=$bytesAlreadyDownloaded-")
                    }
                    connection.instanceFollowRedirects = false
                    connection.connect()

                    val code = connection.responseCode
                    if (code in 301..308) {
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
                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    throw Exception("HTTP $responseCode: ${connection.responseMessage}")
                }

                val isPartialContent  = responseCode == HttpURLConnection.HTTP_PARTIAL
                val contentLength     = connection.contentLengthLong
                val effectiveTotalBytes = when {
                    totalSizeBytes > 0 -> totalSizeBytes
                    isPartialContent   -> bytesAlreadyDownloaded + contentLength
                    contentLength > 0  -> contentLength
                    else               -> 0L
                }

                val inputStream  = connection.inputStream
                val outputStream = FileOutputStream(destFile, isPartialContent)
                val buffer       = ByteArray(128 * 1024)
                var bytesRead: Int
                var totalDownloaded    = if (isPartialContent) bytesAlreadyDownloaded else 0L
                var lastProgressUpdate = 0L
                var lastBytesDownloaded = totalDownloaded

                try {
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        outputStream.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 800) {
                            val timeDiff = now - lastProgressUpdate
                            val bytesDiff = totalDownloaded - lastBytesDownloaded
                            val speedBps = if (timeDiff > 0) (bytesDiff * 1000) / timeDiff else 0L
                            val speedStr = "${formatBytes(speedBps)}/s"

                            updateProgress(modelId, DownloadProgress(
                                modelId          = modelId,
                                state            = DownloadState.DOWNLOADING,
                                bytesDownloaded  = totalDownloaded,
                                totalBytes       = effectiveTotalBytes,
                                downloadSpeed    = speedStr
                            ))
                            val fraction = if (effectiveTotalBytes > 0)
                                (totalDownloaded.toFloat() / effectiveTotalBytes).coerceIn(0f, 1f)
                            else 0f
                            val percent = (fraction * 100).toInt()
                            DownloadForegroundService.update(
                                context,
                                fraction,
                                "$fileName · $percent% · $speedStr"
                            )
                            lastProgressUpdate = now
                            lastBytesDownloaded = totalDownloaded
                        }
                    }
                } finally {
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                }

                destFile.renameTo(finalFile)
                activeFileNames.remove(modelId)
                updateProgress(modelId, DownloadProgress(
                    modelId         = modelId,
                    state           = DownloadState.COMPLETED,
                    bytesDownloaded = totalDownloaded,
                    totalBytes      = effectiveTotalBytes
                ))

            } catch (e: CancellationException) {
                if (pausingModels.remove(modelId)) {
                    val saved = _downloads.value[modelId]
                    updateProgress(modelId, DownloadProgress(
                        modelId         = modelId,
                        state           = DownloadState.PAUSED,
                        bytesDownloaded = saved?.bytesDownloaded ?: 0L,
                        totalBytes      = saved?.totalBytes ?: totalSizeBytes
                    ))
                } else {
                    updateProgress(modelId, DownloadProgress(modelId, DownloadState.CANCELLED))
                }
                // Keep .part file for resuming later
            } catch (e: Exception) {
                destFile.delete()
                updateProgress(modelId, DownloadProgress(
                    modelId      = modelId,
                    state        = DownloadState.FAILED,
                    errorMessage = e.message ?: "Download failed"
                ))
            } finally {
                connection?.disconnect()
                activeJobs.remove(modelId)
                if (activeJobs.isEmpty()) {
                    DownloadForegroundService.stop(context)
                }
            }
        }

        activeJobs[modelId] = job
        if (activeJobs.size == 1) {
            DownloadForegroundService.start(context)
        }
    }

    override fun pauseDownload(modelId: String) {
        pausingModels.add(modelId)
        activeJobs[modelId]?.cancel()
        activeJobs.remove(modelId)
        if (activeJobs.isEmpty()) {
            DownloadForegroundService.stop(context)
        }
    }

    override fun cancelDownload(modelId: String) {
        pausingModels.remove(modelId)
        activeJobs[modelId]?.cancel()
        activeJobs.remove(modelId)
        val fileName = activeFileNames.remove(modelId)
        if (fileName != null) {
            File(modelsDir, "$fileName.part").delete()
        }
        if (activeJobs.isEmpty()) {
            DownloadForegroundService.stop(context)
        }
        updateProgress(modelId, DownloadProgress(modelId, DownloadState.CANCELLED))
    }

    override fun deleteModel(fileName: String) {
        File(modelsDir, fileName).delete()
        File(modelsDir, "$fileName.part").delete()
    }

    override fun clearDownloadState(modelId: String) {
        _downloads.update { it - modelId }
    }

    override fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576     -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1_024         -> "${"%.1f".format(bytes / 1_024.0)} KB"
        else                   -> "$bytes B"
    }

    private fun updateProgress(modelId: String, progress: DownloadProgress) {
        _downloads.update { current -> current + (modelId to progress) }
    }
}
