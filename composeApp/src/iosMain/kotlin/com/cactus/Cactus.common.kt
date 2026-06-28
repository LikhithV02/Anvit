package com.cactus

expect fun cactusInit(modelPath: String, corpusDir: String?, cacheIndex: Boolean): Long
expect fun cactusDestroy(handle: Long)
expect fun cactusReset(handle: Long)
expect fun cactusStop(handle: Long)
expect fun cactusComplete(
    handle: Long,
    messagesJson: String,
    optionsJson: String?,
    toolsJson: String?,
    callback: CactusTokenCallback?,
    pcmData: ByteArray?
): String
expect fun cactusGetLastError(): String
expect fun cactusLogSetLevel(level: Int)
expect fun cactusSetTelemetryEnvironment(framework: String?, cacheLocation: String?, version: String?)
expect fun cactusSetAppId(appId: String)
expect fun cactusTelemetryShutdown()
