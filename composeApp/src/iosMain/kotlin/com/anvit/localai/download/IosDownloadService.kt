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
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.FILE
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.errno
import platform.posix.SEEK_CUR
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.strerror
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

class IosDownloadService : DownloadService {

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    override val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private val activeJobs = mutableMapOf<String, Job>()
    private val activeFileNames = mutableMapOf<String, String>()
    private val pausingModels = mutableSetOf<String>()
    private val lastNotifiedPercent = mutableMapOf<String, Int>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var bgTaskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    init {
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        ) { _, _ -> }
    }

    private fun showNotification(title: String, body: String, identifier: String) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.getNotificationSettingsWithCompletionHandler { settings ->
            if (settings?.authorizationStatus == platform.UserNotifications.UNAuthorizationStatusAuthorized) {
                val content = UNMutableNotificationContent()
                content.setTitle(title)
                content.setBody(body)
                content.setSound(UNNotificationSound.defaultSound())
                val request = UNNotificationRequest.requestWithIdentifier(identifier, content, null)
                center.addNotificationRequest(request) { _ -> }
            }
        }
    }

    private fun beginBackgroundTask() {
        if (bgTaskId == UIBackgroundTaskInvalid) {
            bgTaskId = UIApplication.sharedApplication.beginBackgroundTaskWithExpirationHandler {
                endBackgroundTask()
            }
        }
    }

    private fun endBackgroundTask() {
        if (bgTaskId != UIBackgroundTaskInvalid) {
            UIApplication.sharedApplication.endBackgroundTask(bgTaskId)
            bgTaskId = UIBackgroundTaskInvalid
        }
    }

    // Ktor Darwin engine buffers the entire response body in memory before exposing it.
    // Large files must be downloaded as multiple 100 MB Range requests to stay within limits.
    private val httpClient = HttpClient(Darwin) {
        expectSuccess = false
        engine {
            configureSession {
                timeoutIntervalForRequest = 300.0
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
            val documentsPath = docsDir?.path ?: "${NSHomeDirectory()}/Documents"
            ensureDirectory(documentsPath)
            val dir = "$documentsPath/models"
            ensureDirectory(dir)
            return dir
        }

    @OptIn(ExperimentalForeignApi::class)
    override fun isModelPresent(fileName: String): Boolean {
        val path = "$modelsDir/$fileName"
        // Directory-based Cactus models are complete only after extraction writes the sentinel.
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
    private fun ensureDirectory(path: String) {
        val created = NSFileManager.defaultManager.createDirectoryAtPath(
            path, withIntermediateDirectories = true, attributes = null, error = null
        )
        if (!created && !NSFileManager.defaultManager.fileExistsAtPath(path)) {
            throw Exception("Cannot create directory: $path")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun removePathIfExists(path: String) {
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun openWritableFile(path: String, append: Boolean): CPointer<FILE> {
        return fopen(path, if (append) "ab" else "wb")
            ?: throw Exception("Cannot open file for writing: $path (${posixErrorMessage()})")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun posixErrorMessage(): String {
        val message = strerror(errno)?.toKString()
        return if (message.isNullOrBlank()) "errno=$errno" else message
    }

    private fun safeHuggingFacePath(path: String): String {
        val normalized = path.replace('\\', '/').trim()
        if (
            normalized.isBlank() ||
            normalized.startsWith("/") ||
            normalized.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) {
            throw Exception("Unsafe HuggingFace file path: $path")
        }
        return normalized
    }

    private fun safeArchiveEntryPath(path: String): String {
        val normalized = path.replace('\\', '/').trim().trimStart('/')
        if (
            normalized.isBlank() ||
            normalized.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) {
            throw Exception("Unsafe archive entry path: $path")
        }
        return normalized
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun unzipArchive(zipPath: String, destDir: String) {
        val input = fopen(zipPath, "rb") ?: throw Exception("Cannot open zip archive: $zipPath (${posixErrorMessage()})")
        try {
            for (entry in readZipEntries(input)) {
                val entryPath = safeArchiveEntryPath(entry.name)
                val outputPath = "$destDir/$entryPath"

                if (entry.name.endsWith("/")) {
                    ensureDirectory(outputPath)
                    continue
                }

                outputPath.substringBeforeLast('/', missingDelimiterValue = "")
                    .takeIf { it.isNotBlank() }
                    ?.let { ensureDirectory(it) }

                seekOrThrow(input, entry.localHeaderOffset)
                val localHeader = readExact(input, ZIP_LOCAL_HEADER_SIZE)
                if (localHeader.readUIntLE(0) != ZIP_LOCAL_SIGNATURE) {
                    throw Exception("Invalid zip local header for ${entry.name}")
                }
                val nameLen = localHeader.readUShortLE(26).toLong()
                val extraLen = localHeader.readUShortLE(28).toLong()
                seekRelativeOrThrow(input, nameLen + extraLen)

                val output = openWritableFile(outputPath, append = false)
                try {
                    when (entry.method) {
                        ZIP_METHOD_STORED -> copyStoredZipEntry(input, output, entry.compressedSize)
                        ZIP_METHOD_DEFLATED -> inflateZipEntry(input, output, entry.compressedSize)
                        else -> throw Exception("Unsupported zip compression method ${entry.method} in ${entry.name}")
                    }
                } finally {
                    fclose(output)
                }
            }
        } finally {
            fclose(input)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readZipEntries(file: CPointer<FILE>): List<ZipEntryMeta> {
        seekOrThrow(file, 0L, SEEK_END)
        val fileSize = ftell(file).toLong()
        if (fileSize < ZIP_END_OF_CENTRAL_DIRECTORY_SIZE) throw Exception("Zip archive is too small")

        val tailSize = minOf(fileSize, ZIP_EOCD_SEARCH_WINDOW).toInt()
        seekOrThrow(file, fileSize - tailSize)
        val tail = readExact(file, tailSize)
        val eocdOffsetInTail = findEndOfCentralDirectory(tail)
            ?: throw Exception("Zip end-of-central-directory record not found")
        val centralDirectorySize = tail.readUIntLE(eocdOffsetInTail + 12).toLong()
        val centralDirectoryOffset = tail.readUIntLE(eocdOffsetInTail + 16).toLong()
        val centralDirectoryEnd = centralDirectoryOffset + centralDirectorySize

        seekOrThrow(file, centralDirectoryOffset)
        val entries = mutableListOf<ZipEntryMeta>()
        while (ftell(file).toLong() < centralDirectoryEnd) {
            val header = readExact(file, ZIP_CENTRAL_DIRECTORY_HEADER_SIZE)
            if (header.readUIntLE(0) != ZIP_CENTRAL_DIRECTORY_SIGNATURE) {
                throw Exception("Invalid zip central directory header")
            }
            val method = header.readUShortLE(10).toInt()
            var compressedSize = header.readUIntLE(20)
            var uncompressedSize = header.readUIntLE(24)
            val nameLen = header.readUShortLE(28).toInt()
            val extraLen = header.readUShortLE(30).toInt()
            val commentLen = header.readUShortLE(32).toLong()
            var localHeaderOffset = header.readUIntLE(42)
            val name = readExact(file, nameLen).decodeToString()
            val extra = readExact(file, extraLen)
            val zip64 = parseZip64Extra(extra)
            if (uncompressedSize == ZIP32_MAX) uncompressedSize = zip64.nextValue("uncompressed size", name)
            if (compressedSize == ZIP32_MAX) compressedSize = zip64.nextValue("compressed size", name)
            if (localHeaderOffset == ZIP32_MAX) localHeaderOffset = zip64.nextValue("local header offset", name)
            seekRelativeOrThrow(file, commentLen)
            entries += ZipEntryMeta(name, method, compressedSize, uncompressedSize, localHeaderOffset)
        }
        return entries
    }

    private fun parseZip64Extra(extra: ByteArray): Zip64Values {
        var cursor = 0
        while (cursor <= extra.size - 4) {
            val tag = extra.readUShortLE(cursor)
            val size = extra.readUShortLE(cursor + 2)
            val dataStart = cursor + 4
            val dataEnd = dataStart + size
            if (dataEnd > extra.size) break
            if (tag == ZIP64_EXTRA_TAG) {
                val values = mutableListOf<Long>()
                var valueOffset = dataStart
                while (valueOffset <= dataEnd - 8) {
                    values += extra.readULongLE(valueOffset)
                    valueOffset += 8
                }
                return Zip64Values(values)
            }
            cursor = dataEnd
        }
        return Zip64Values(emptyList())
    }

    private fun findEndOfCentralDirectory(tail: ByteArray): Int? {
        for (i in tail.size - ZIP_END_OF_CENTRAL_DIRECTORY_SIZE downTo 0) {
            if (tail.readUIntLE(i) == ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE) return i
        }
        return null
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readExact(file: CPointer<FILE>, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        bytes.usePinned { pinned ->
            while (offset < size) {
                val read = fread(pinned.addressOf(offset), 1u, (size - offset).toULong(), file).toInt()
                if (read <= 0) throw Exception("Unexpected end of zip archive")
                offset += read
            }
        }
        return bytes
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun copyStoredZipEntry(input: CPointer<FILE>, output: CPointer<FILE>, compressedSize: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = compressedSize
        buffer.usePinned { pinned ->
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = fread(pinned.addressOf(0), 1u, toRead.toULong(), input).toInt()
                if (read <= 0) throw Exception("Unexpected end of stored zip entry")
                fwrite(pinned.addressOf(0), 1u, read.toULong(), output)
                remaining -= read
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun inflateZipEntry(input: CPointer<FILE>, output: CPointer<FILE>, compressedSize: Long) {
        val stream = nativeHeap.alloc<z_stream>()
        val inputBuffer = ByteArray(64 * 1024)
        val outputBuffer = ByteArray(64 * 1024)
        var remaining = compressedSize
        try {
            if (inflateInit2(stream.ptr, -15) != Z_OK) throw Exception("Cannot initialize zlib inflate")
            inputBuffer.usePinned { inPinned ->
                outputBuffer.usePinned { outPinned ->
                    do {
                        if (stream.avail_in == 0u && remaining > 0) {
                            val toRead = minOf(inputBuffer.size.toLong(), remaining).toInt()
                            val read = fread(inPinned.addressOf(0), 1u, toRead.toULong(), input).toInt()
                            if (read <= 0) throw Exception("Unexpected end of deflated zip entry")
                            remaining -= read
                            stream.next_in = inPinned.addressOf(0).reinterpret()
                            stream.avail_in = read.toUInt()
                        }

                        stream.next_out = outPinned.addressOf(0).reinterpret()
                        stream.avail_out = outputBuffer.size.toUInt()
                        val rc = inflate(stream.ptr, Z_NO_FLUSH)
                        if (rc != Z_OK && rc != Z_STREAM_END) throw Exception("zlib inflate failed with code $rc")

                        val produced = outputBuffer.size - stream.avail_out.toInt()
                        if (produced > 0) {
                            fwrite(outPinned.addressOf(0), 1u, produced.toULong(), output)
                        }
                    } while (rc != Z_STREAM_END)
                }
            }
        } finally {
            inflateEnd(stream.ptr)
            nativeHeap.free(stream)
        }
    }

    private fun ByteArray.readUShortLE(offset: Int): Int =
        ((this[offset + 1].toInt() and 0xFF) shl 8) or (this[offset].toInt() and 0xFF)

    private fun ByteArray.readUIntLE(offset: Int): Long =
        ((this[offset + 3].toLong() and 0xFF) shl 24) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            (this[offset].toLong() and 0xFF)

    private fun ByteArray.readULongLE(offset: Int): Long =
        (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24) or
            ((this[offset + 4].toLong() and 0xFF) shl 32) or
            ((this[offset + 5].toLong() and 0xFF) shl 40) or
            ((this[offset + 6].toLong() and 0xFF) shl 48) or
            ((this[offset + 7].toLong() and 0xFF) shl 56)

    @OptIn(ExperimentalForeignApi::class)
    private fun seekOrThrow(file: CPointer<FILE>, offset: Long, whence: Int = SEEK_SET) {
        if (fseek(file, offset, whence) != 0) throw Exception("Cannot seek zip archive (${posixErrorMessage()})")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun seekRelativeOrThrow(file: CPointer<FILE>, offset: Long) {
        if (offset == 0L) return
        seekOrThrow(file, offset, SEEK_CUR)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun startDownload(
        modelId: String,
        fileName: String,
        downloadUrl: String,
        totalSizeBytes: Long,
        authToken: String,
        archiveFileName: String?
    ) {
        activeJobs[modelId]?.cancel()
        activeFileNames[modelId] = archiveFileName ?: fileName

        lastNotifiedPercent[modelId] = 0

        val job = scope.launch {
            beginBackgroundTask()
            showNotification("Downloading Model", "Started downloading $fileName", "dl_$modelId")
            updateProgress(modelId, DownloadProgress(modelId, DownloadState.DOWNLOADING, totalBytes = totalSizeBytes))
            try {
                if (archiveFileName != null) {
                    downloadAndUnzipArchive(modelId, fileName, downloadUrl, archiveFileName, totalSizeBytes, authToken)
                } else if (downloadUrl.startsWith("hf://")) {
                    val repoId = downloadUrl.removePrefix("hf://")
                    downloadHuggingFaceDir(modelId, fileName, repoId, totalSizeBytes, authToken)
                } else {
                    downloadSingleFile(modelId, downloadUrl, fileName, totalSizeBytes, authToken)
                }
                activeFileNames.remove(modelId)
                showNotification("Download Complete", "$fileName is ready to use", "dl_$modelId")
            } catch (e: CancellationException) {
                if (pausingModels.remove(modelId)) {
                    val saved = _downloads.value[modelId]
                    showNotification("Download Paused", "Download of $fileName paused", "dl_$modelId")
                    updateProgress(modelId, DownloadProgress(
                        modelId         = modelId,
                        state           = DownloadState.PAUSED,
                        bytesDownloaded = saved?.bytesDownloaded ?: 0L,
                        totalBytes      = saved?.totalBytes ?: totalSizeBytes
                    ))
                } else {
                    updateProgress(modelId, DownloadProgress(modelId, DownloadState.CANCELLED))
                }
            } catch (e: Exception) {
                showNotification("Download Failed", "Failed to download $fileName: ${e.message}", "dl_$modelId")
                updateProgress(modelId, DownloadProgress(
                    modelId      = modelId,
                    state        = DownloadState.FAILED,
                    errorMessage = e.message ?: "Download failed"
                ))
            } finally {
                lastNotifiedPercent.remove(modelId)
                activeJobs.remove(modelId)
                if (activeJobs.isEmpty()) {
                    endBackgroundTask()
                }
            }
        }

        activeJobs[modelId] = job
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun downloadAndUnzipArchive(
        modelId: String,
        dirName: String,
        repoUrl: String,
        archiveFileName: String,
        totalSizeBytes: Long,
        authToken: String
    ) {
        val repoId = repoUrl.removePrefix("hf://")
        val safeArchive = safeHuggingFacePath(archiveFileName)
        val archiveUrl = "https://huggingface.co/$repoId/resolve/main/$safeArchive"
        val archivePath = "$modelsDir/$safeArchive"
        val finalDir = "$modelsDir/$dirName"
        val unpackDir = "$modelsDir/$dirName.unpack"

        downloadSingleFile(modelId, archiveUrl, safeArchive, totalSizeBytes, authToken)

        removePathIfExists(unpackDir)
        ensureDirectory(unpackDir)
        unzipArchive(archivePath, unpackDir)

        val sentinel = openWritableFile("$unpackDir/.complete", append = false)
        fclose(sentinel)

        removePathIfExists(finalDir)
        if (!NSFileManager.defaultManager.moveItemAtPath(unpackDir, toPath = finalDir, error = null)) {
            throw Exception("Cannot finalize unzipped model directory: $finalDir")
        }
        removePathIfExists(archivePath)

        updateProgress(modelId, DownloadProgress(
            modelId = modelId,
            state = DownloadState.COMPLETED,
            bytesDownloaded = totalSizeBytes,
            totalBytes = totalSizeBytes
        ))
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

        if (NSFileManager.defaultManager.fileExistsAtPath(destPath) && currentOffset <= 0L) {
            removePathIfExists(destPath)
        }
        val file = openWritableFile(destPath, append = currentOffset > 0L)

        var lastProgressUpdate = 0L
        var lastBytesDownloaded = currentOffset
        var consecutiveTransientFailures = 0

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

                try {
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
                    consecutiveTransientFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (
                        !isRetriableDownloadFailure(e.message) ||
                        consecutiveTransientFailures >= MAX_TRANSIENT_DOWNLOAD_RETRIES
                    ) {
                        throw e
                    }
                    consecutiveTransientFailures++
                    delay(downloadRetryDelayMillis(consecutiveTransientFailures))
                }
            }
        } finally {
            fclose(file)
        }

        removePathIfExists(finalPath)
        if (!NSFileManager.defaultManager.moveItemAtPath(destPath, toPath = finalPath, error = null)) {
            throw Exception("Cannot finalize downloaded file: $finalPath")
        }

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
        ensureDirectory(destDir)

        var completedBytes = 0L
        var lastProgressUpdate = 0L

        for ((rawFilePath, fileSizeHint) in files) {
            currentCoroutineContext().ensureActive()

            val filePath = safeHuggingFacePath(rawFilePath)
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
                ensureDirectory(parentDir)
            }

            val fileUrl = "https://huggingface.co/$repoId/resolve/main/$filePath"
            var fileOffset = if (NSFileManager.defaultManager.fileExistsAtPath(partPath)) fileSize(partPath) else 0L
            var effectiveFileSize = fileSizeHint

            if (NSFileManager.defaultManager.fileExistsAtPath(partPath) && fileOffset <= 0L) {
                removePathIfExists(partPath)
            }
            val file = openWritableFile(partPath, append = fileOffset > 0L)

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

            removePathIfExists(localPath)
            if (!NSFileManager.defaultManager.moveItemAtPath(partPath, toPath = localPath, error = null)) {
                throw Exception("Cannot finalize downloaded file: $localPath")
            }
            completedBytes += if (effectiveFileSize > 0L) effectiveFileSize else fileOffset
        }

        // Write sentinel only after every file is on disk — isModelPresent checks for this.
        val sentinel = openWritableFile("$destDir/.complete", append = false)
        fclose(sentinel)

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

    override fun pauseDownload(modelId: String) {
        pausingModels.add(modelId)
        activeJobs[modelId]?.cancel()
        activeJobs.remove(modelId)
        if (activeJobs.isEmpty()) {
            endBackgroundTask()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun cancelDownload(modelId: String) {
        pausingModels.remove(modelId)
        activeJobs[modelId]?.cancel()
        activeJobs.remove(modelId)
        val fileName = activeFileNames.remove(modelId)
        if (fileName != null) {
            NSFileManager.defaultManager.removeItemAtPath("$modelsDir/$fileName.part", error = null)
        }
        if (activeJobs.isEmpty()) {
            endBackgroundTask()
        }
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
        if (progress.state == DownloadState.DOWNLOADING && progress.totalBytes > 0) {
            val percent = ((progress.bytesDownloaded * 100) / progress.totalBytes).toInt()
            val last = lastNotifiedPercent[modelId] ?: 0
            // Notify at 25%, 50%, 75% milestones
            val milestone = (percent / 25) * 25
            if (milestone > last && milestone < 100) {
                lastNotifiedPercent[modelId] = milestone
                showNotification(
                    "Downloading — $milestone%",
                    "${formatBytes(progress.bytesDownloaded)} of ${formatBytes(progress.totalBytes)}",
                    "dl_progress_$modelId"
                )
            }
        }
    }

    companion object {
        private const val CHUNK_SIZE = 100L * 1024 * 1024  // 100 MB per Range request
        private const val ZIP_LOCAL_SIGNATURE = 0x04034B50L
        private const val ZIP_CENTRAL_DIRECTORY_SIGNATURE = 0x02014B50L
        private const val ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054B50L
        private const val ZIP_LOCAL_HEADER_SIZE = 30
        private const val ZIP_CENTRAL_DIRECTORY_HEADER_SIZE = 46
        private const val ZIP_END_OF_CENTRAL_DIRECTORY_SIZE = 22
        private const val ZIP_EOCD_SEARCH_WINDOW = 65_557L
        private const val ZIP_METHOD_STORED = 0
        private const val ZIP_METHOD_DEFLATED = 8
        private const val ZIP32_MAX = 0xFFFF_FFFFL
        private const val ZIP64_EXTRA_TAG = 0x0001
    }
}

private data class ZipEntryMeta(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long
)

private class Zip64Values(private val values: List<Long>) {
    private var index = 0

    fun nextValue(label: String, entryName: String): Long {
        if (index >= values.size) throw Exception("Missing ZIP64 $label for $entryName")
        return values[index++]
    }
}
