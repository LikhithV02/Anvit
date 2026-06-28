package com.cactus

import cactus.cactus_complete
import cactus.cactus_destroy
import cactus.cactus_get_last_error
import cactus.cactus_init
import cactus.cactus_log_set_level
import cactus.cactus_reset
import cactus.cactus_set_app_id
import cactus.cactus_set_telemetry_environment
import cactus.cactus_stop
import cactus.cactus_telemetry_shutdown
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
actual fun cactusInit(modelPath: String, corpusDir: String?, cacheIndex: Boolean): Long {
    val ptr = cactus_init(modelPath, corpusDir, cacheIndex)
        ?: throw RuntimeException(cactusGetLastError().ifBlank { "Failed to initialize Cactus model" })
    return ptr.rawValue.toLong()
}

@OptIn(ExperimentalForeignApi::class)
actual fun cactusDestroy(handle: Long) {
    cactus_destroy(handle.toCPointer())
}

@OptIn(ExperimentalForeignApi::class)
actual fun cactusReset(handle: Long) {
    cactus_reset(handle.toCPointer())
}

@OptIn(ExperimentalForeignApi::class)
actual fun cactusStop(handle: Long) {
    cactus_stop(handle.toCPointer())
}

@OptIn(ExperimentalForeignApi::class)
actual fun cactusComplete(
    handle: Long,
    messagesJson: String,
    optionsJson: String?,
    toolsJson: String?,
    callback: CactusTokenCallback?,
    pcmData: ByteArray?
): String = memScoped {
    val bufferSize = 1024 * 1024
    val buffer = allocArray<ByteVar>(bufferSize)
    val callbackRef = callback?.let { StableRef.create(it) }
    val pcmPtr = pcmData?.refTo(0)?.getPointer(this)
    try {
        val result = cactus_complete(
            handle.toCPointer(),
            messagesJson,
            buffer,
            bufferSize.toULong(),
            optionsJson,
            toolsJson,
            callbackRef?.let {
                staticCFunction<CPointer<ByteVar>?, UInt, COpaquePointer?, Unit> { token, tokenId, userData ->
                    if (token != null && userData != null) {
                        userData.asStableRef<CactusTokenCallback>().get().onToken(token.toKString(), tokenId.toInt())
                    }
                }
            },
            callbackRef?.asCPointer(),
            pcmPtr?.reinterpret(),
            pcmData?.size?.toULong() ?: 0u
        )
        if (result < 0) throw RuntimeException(cactusGetLastError().ifBlank { "cactus_complete failed" })
        buffer.toKString()
    } finally {
        callbackRef?.dispose()
    }
}

actual fun cactusGetLastError(): String = cactus_get_last_error()?.toKString() ?: ""

actual fun cactusLogSetLevel(level: Int) {
    cactus_log_set_level(level)
}

actual fun cactusSetTelemetryEnvironment(framework: String?, cacheLocation: String?, version: String?) {
    cactus_set_telemetry_environment(framework, cacheLocation, version)
}

actual fun cactusSetAppId(appId: String) {
    cactus_set_app_id(appId)
}

actual fun cactusTelemetryShutdown() {
    cactus_telemetry_shutdown()
}
