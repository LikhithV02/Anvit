package com.cactus

actual fun cactusInit(modelPath: String, corpusDir: String?, cacheIndex: Boolean): Long = cactusUnavailable()
actual fun cactusDestroy(handle: Long): Unit = cactusUnavailable()
actual fun cactusReset(handle: Long): Unit = cactusUnavailable()
actual fun cactusStop(handle: Long): Unit = cactusUnavailable()
actual fun cactusComplete(handle: Long, messagesJson: String, optionsJson: String?, toolsJson: String?, callback: CactusTokenCallback?, pcmData: ByteArray?): String = cactusUnavailable()
actual fun cactusGetLastError(): String = "Cactus unavailable in Simulator"
actual fun cactusLogSetLevel(level: Int): Unit = cactusUnavailable()
actual fun cactusSetTelemetryEnvironment(framework: String?, cacheLocation: String?, version: String?): Unit = cactusUnavailable()
actual fun cactusSetAppId(appId: String): Unit = cactusUnavailable()
actual fun cactusTelemetryShutdown(): Unit = cactusUnavailable()

private fun cactusUnavailable(): Nothing = error("Cactus unavailable in Simulator")
