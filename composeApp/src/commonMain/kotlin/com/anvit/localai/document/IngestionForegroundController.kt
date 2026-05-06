package com.anvit.localai.document

/**
 * Platform-agnostic hook for keeping document ingestion alive in the background.
 * Android binds this to a foreground Service + WakeLock + persistent notification.
 * iOS uses [NoOp] — Metal/MLX work survives normal app lifecycle without a service.
 */
interface IngestionForegroundController {
    fun start(initialStatus: String)
    fun update(fraction: Float, status: String)
    fun stop()

    object NoOp : IngestionForegroundController {
        override fun start(initialStatus: String) {}
        override fun update(fraction: Float, status: String) {}
        override fun stop() {}
    }
}
