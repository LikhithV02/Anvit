package com.anvit.localai.download

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.client.plugins.expectSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.anvit.localai.utils.currentTimeMillis
import platform.Foundation.NSFileManager
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

class IosDownloadService : DownloadService {

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    override val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private val activeJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Ktor Darwin engine buffers the entire response body in memory before exposing it.
    // Large files must be downloaded as multiple 100 MB Range requests to stay within limits.
    private val httpClient = HttpClient(Darwin) {
        expectSuccess = false
        engine {
            configureSession {
                timeoutIntervalForRequest = 60.0
                timeoutIntervalForResource = 3600.0
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private val modelsDir: String
        get() {
            val docsDir = NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null
            )
            val dir = (docsDir?.path ?: "") + "/models"
            NSFileManager.defaultManager.createDirectoryAtPath(
                dir, withIntermediateDirectories = true, attributes = null, error = null
            )
            return dir
        }

    @OptIn(ExperimentalForeignApi::class)
    override fun isModelPresent(fileName: String): Boolean {
        val path = "$modelsDir/$fileName"
        // Directory-based MLX models: only consider complete when the sentinel exists,
        // written by downloadHuggingFaceDir after every file is successfully saved.
        return if (!fileName.contains('.')) {
            NSFileManager.defaultManager.fileExistsAtPath("$path/.complete")
        } else {
            NSFileManager.defaultManager.fileExistsAtPath(path)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun fileSize(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
        return (attrs?.get("NSFileSize") as? Long) ?: 0L
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun startDownload(
        modelId: String,
        fileName: String,
        downloadUrl: String,
        totalSizeBytes: Long,
        authToken: String
    ) {
        activeJobs[modelId]?.cancel()

        val job = scope.launch {
            updateProgress(modelId, DownloadProgress(modelId, DownloadState.DOWNLOADING, totalBytes = totalSizeBytes))
            try {
                if (downloadUrl.startsWith("hf://")) {
                    val repoId = downloadUrl.removePrefix("hf://")
                    downloadHuggingFaceDir(modelId, fileName, repoId, totalSizeBytes, authToken)
                } else {
                    downloadSingleFile(modelId, downloadUrl, fileName, totalSizeBytes, authToken)
                }
            } catch (e: CancellationException) {
                // Keep partial files on disk so downloads can resume next time
                updateProgress(modelId, DownloadProgress(modelId, DownloadState.CANCELLED))
            } catch (e: Exception) {
                updateProgress(modelId, DownloadProgress(
                    modelId      = modelId,
                    state        = DownloadState.FAILED,
                    errorMessage = e.message ?: "Download failed"
                ))
            } finally {
                activeJobs.remove(modelId)
            }
        }

        activeJobs[modelId] = job
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun downloadSingleFile(
        modelId: String,
        downloadUrl: String,
        fileName: String,
        totalSizeBytes: Long,
        authToken: String
    ) {
        val destPath = "$modelsDir/$fileName.part"
        val finalPath = "$modelsDir/$fileName"

        var currentOffset =
            if (NSFileManager.defaultManager.fileExistsAtPath(destPath)) fileSize(destPath) else 0L
        var effectiveTotalBytes = totalSizeBytes

        val file = fopen(destPath, if (currentOffset > 0) "ab" else "wb")
            ?: throw Exception("Cannot open file for writing: $destPath")

        var lastProgressUpdate = 0L
        var lastBytesDownloaded = currentOffset

        try {
            var downloadFinished = false
            while (!downloadFinished) {
                currentCoroutineContext().ensureActive()

                val currentSnapshot = currentOffset
                val totalSnapshot = effectiveTotalBytes

                if (totalSnapshot > 0L && currentSnapshot >= totalSnapshot) {
                    downloadFinished = true
                    break
                }

                val rangeEnd = if (totalSnapshot > 0L) {
                    val nextEnd = currentSnapshot + CHUNK_SIZE - 1L
                    if (nextEnd >= totalSnapshot) totalSnapshot - 1L else nextEnd
                } else {
                    currentSnapshot + CHUNK_SIZE - 1L
                }

                if (rangeEnd < currentSnapshot) {
                    downloadFinished = true
                    break
                }

                downloadFinished = httpClient.prepareGet(downloadUrl) {
                    header("User-Agent", "AnvitApp/1.0")
                    header("Accept", "application/octet-stream")
                    if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken")
                    header("Range", "bytes=$currentSnapshot-$rangeEnd")
                }.execute { response ->
                    val status = response.status

                    if (status == HttpStatusCode.RequestedRangeNotSatisfiable) return@execute true

                    if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden)
                        throw Exception("HTTP ${status.value}: This model is gated — enter a HuggingFace token in Settings")

                    if (status != HttpStatusCode.OK && status != HttpStatusCode.PartialContent)
                        throw Exception("HTTP ${status.value}: ${status.description}")

                    if (effectiveTotalBytes == 0L) {
                        val contentRange = response.headers["Content-Range"]
                        if (contentRange != null) {
                            effectiveTotalBytes = contentRange.substringAfterLast('/').toLongOrNull() ?: 0L
                        }
                        if (effectiveTotalBytes == 0L) {
                            val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
                            effectiveTotalBytes = if (status == HttpStatusCode.PartialContent) contentLength + currentOffset else contentLength
                        }
                    }

                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                        if (bytesRead <= 0) break

                        buffer.usePinned { pinned: Pinned<ByteArray> ->
                            fwrite(pinned.addressOf(0), 1uL, bytesRead.toULong(), file)
                        }
                        currentOffset += bytesRead

                        val now = currentTimeMillis()
                        if (now - lastProgressUpdate > 800L || (effectiveTotalBytes > 0L && currentOffset >= effectiveTotalBytes)) {
                            val timeDiff = if (now - lastProgressUpdate > 0L) now - lastProgressUpdate else 1L
                            val bytesDiff = currentOffset - lastBytesDownloaded
                            val speedBps = (bytesDiff * 1000L) / timeDiff

                            updateProgress(modelId, DownloadProgress(
                                modelId         = modelId,
                                state           = DownloadState.DOWNLOADING,
                                bytesDownloaded = currentOffset,
                                totalBytes      = effectiveTotalBytes,
                                downloadSpeed   = "${formatBytes(speedBps)}/s"
                            ))
                            lastProgressUpdate  = now
                            lastBytesDownloaded = currentOffset
                        }
                    }

                    status == HttpStatusCode.OK || (effectiveTotalBytes > 0L && currentOffset >= effectiveTotalBytes)
                }
            }
        } finally {
            fclose(file)
        }

        NSFileManager.defaultManager.removeItemAtPath(finalPath, error = null)
        NSFileManager.defaultManager.moveItemAtPath(destPath, toPath = finalPath, error = null)

        updateProgress(modelId, DownloadProgress(
            modelId         = modelId,
            state           = DownloadState.COMPLETED,
            bytesDownloaded = currentOffset,
            totalBytes      = if (effectiveTotalBytes > 0) effectiveTotalBytes else currentOffset
        ))
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun downloadHuggingFaceDir(
        modelId: String,
        dirName: String,
        repoId: String,
        hintTotalBytes: Long,
        authToken: String
    ) {
        val files = fetchHfTree(repoId, authToken)
        if (files.isEmpty()) throw Exception("Could not fetch file list from HuggingFace for $repoId")

        val totalBytes = if (hintTotalBytes > 0) hintTotalBytes else files.sumOf { it.second }
        val destDir = "$modelsDir/$dirName"
        NSFileManager.defaultManager.createDirectoryAtPath(
            destDir, withIntermediateDirectories = true, attributes = null, error = null
        )

        var completedBytes = 0L
        var lastProgressUpdate = 0L

        for ((filePath, fileSizeHint) in files) {
            currentCoroutineContext().ensureActive()

            val localPath = "$destDir/$filePath"
            val partPath  = "$localPath.part"

            // Skip files already fully downloaded (e.g. resuming after a previous run)
            if (NSFileManager.defaultManager.fileExistsAtPath(localPath)) {
                completedBytes += if (fileSizeHint > 0) fileSizeHint else fileSize(localPath)
                updateProgress(modelId, DownloadProgress(
                    modelId = modelId, state = DownloadState.DOWNLOADING,
                    bytesDownloaded = completedBytes, totalBytes = totalBytes
                ))
                continue
            }

            // Ensure parent subdirectory exists (for nested paths inside the repo)
            val parentDir = localPath.substringBeforeLast('/')
            if (parentDir != localPath) {
                NSFileManager.defaultManager.createDirectoryAtPath(
                    parentDir, withIntermediateDirectories = true, attributes = null, error = null
                )
            }

            val fileUrl = "https://huggingface.co/$repoId/resolve/main/$filePath"
            var fileOffset = if (NSFileManager.defaultManager.fileExistsAtPath(partPath)) fileSize(partPath) else 0L
            var effectiveFileSize = fileSizeHint

            val file = fopen(partPath, if (fileOffset > 0) "ab" else "wb")
                ?: throw Exception("Cannot open: $partPath")

            try {
                var fileFinished = false
                while (!fileFinished) {
                    currentCoroutineContext().ensureActive()

                    if (effectiveFileSize > 0L && fileOffset >= effectiveFileSize) break

                    val rangeEnd = if (effectiveFileSize > 0L) {
                        val end = fileOffset + CHUNK_SIZE - 1L
                        if (end >= effectiveFileSize) effectiveFileSize - 1L else end
                    } else {
                        fileOffset + CHUNK_SIZE - 1L
                    }

                    fileFinished = httpClient.prepareGet(fileUrl) {
                        header("User-Agent", "AnvitApp/1.0")
                        header("Accept", "application/octet-stream")
                        if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken")
                        header("Range", "bytes=$fileOffset-$rangeEnd")
                    }.execute { response ->
                        val status = response.status

                        if (status == HttpStatusCode.RequestedRangeNotSatisfiable) return@execute true
                        if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden)
                            throw Exception("HTTP ${status.value}: This model is gated — enter a HuggingFace token in Settings")
                        if (status != HttpStatusCode.OK && status != HttpStatusCode.PartialContent)
                            throw Exception("HTTP ${status.value}: ${status.description}")

                        if (effectiveFileSize == 0L) {
                            val cr = response.headers["Content-Range"]
                            if (cr != null) effectiveFileSize = cr.substringAfterLast('/').toLongOrNull() ?: 0L
                            if (effectiveFileSize == 0L) {
                                val cl = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
                                effectiveFileSize = if (status == HttpStatusCode.PartialContent) cl + fileOffset else cl
                            }
                        }

                        val channel = response.bodyAsChannel()
                        val buffer  = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                            if (bytesRead <= 0) break

                            buffer.usePinned { pinned: Pinned<ByteArray> ->
                                fwrite(pinned.addressOf(0), 1uL, bytesRead.toULong(), file)
                            }
                            fileOffset += bytesRead

                            val now = currentTimeMillis()
                            if (now - lastProgressUpdate > 800L) {
                                updateProgress(modelId, DownloadProgress(
                                    modelId         = modelId,
                                    state           = DownloadState.DOWNLOADING,
                                    bytesDownloaded = completedBytes + fileOffset,
                                    totalBytes      = totalBytes
                                ))
                                lastProgressUpdate = now
                            }
                        }

                        status == HttpStatusCode.OK || (effectiveFileSize > 0L && fileOffset >= effectiveFileSize)
                    }
                }
            } finally {
                fclose(file)
            }

            NSFileManager.defaultManager.removeItemAtPath(localPath, error = null)
            NSFileManager.defaultManager.moveItemAtPath(partPath, toPath = localPath, error = null)
            completedBytes += if (effectiveFileSize > 0L) effectiveFileSize else fileOffset
        }

        // Write sentinel only after every file is on disk — isModelPresent checks for this.
        val sentinel = fopen("$destDir/.complete", "w")
        if (sentinel != null) fclose(sentinel)

        updateProgress(modelId, DownloadProgress(
            modelId         = modelId,
            state           = DownloadState.COMPLETED,
            bytesDownloaded = completedBytes,
            totalBytes      = if (totalBytes > 0) totalBytes else completedBytes
        ))
    }

    private suspend fun fetchHfTree(repoId: String, authToken: String): List<Pair<String, Long>> {
        val treeUrl  = "https://huggingface.co/api/models/$repoId/tree/main"
        val jsonText = httpClient.prepareGet(treeUrl) {
            header("User-Agent", "AnvitApp/1.0")
            header("Accept", "application/json")
            if (authToken.isNotBlank()) header("Authorization", "Bearer $authToken")
        }.execute { response ->
            if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden)
                throw Exception("HTTP ${response.status.value}: This model is gated — enter a HuggingFace token in Settings")
            if (response.status != HttpStatusCode.OK)
                throw Exception("HTTP ${response.status.value}: Could not fetch HuggingFace model file list")
            response.bodyAsText()
        }
        return parseHfTreeJson(jsonText)
    }

    private fun parseHfTreeJson(jsonString: String): List<Pair<String, Long>> = runCatching {
        Json.parseToJsonElement(jsonString).jsonArray.mapNotNull { element ->
            val obj  = element.jsonObject
            if (obj["type"]?.jsonPrimitive?.content != "file") return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
            Pair(path, size)
        }
    }.getOrDefault(emptyList())

    override fun cancelDownload(modelId: String) {
        activeJobs[modelId]?.cancel()
        activeJobs.remove(modelId)
        updateProgress(modelId, DownloadProgress(modelId, DownloadState.CANCELLED))
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun deleteModel(fileName: String) {
        val path     = "$modelsDir/$fileName"
        val partPath = "$modelsDir/$fileName.part"
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        NSFileManager.defaultManager.removeItemAtPath(partPath, error = null)
    }

    override fun clearDownloadState(modelId: String) {
        _downloads.update { it - modelId }
    }

    private fun roundOneDecimal(value: Double): String {
        val rounded = kotlin.math.round(value * 10) / 10.0
        val str = rounded.toString()
        return if ('.' !in str) "$str.0"
        else str.substringBefore('.') + "." + str.substringAfter('.').take(1)
    }

    override fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "${roundOneDecimal(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576     -> "${roundOneDecimal(bytes / 1_048_576.0)} MB"
        bytes >= 1_024         -> "${roundOneDecimal(bytes / 1_024.0)} KB"
        else                   -> "$bytes B"
    }

    private fun updateProgress(modelId: String, progress: DownloadProgress) {
        _downloads.update { current -> current + (modelId to progress) }
    }

    companion object {
        private const val CHUNK_SIZE = 100L * 1024 * 1024  // 100 MB per Range request
    }
}
