package com.anvit.localai.download

internal const val MAX_TRANSIENT_DOWNLOAD_RETRIES = 4

internal fun isRetriableDownloadFailure(message: String?): Boolean {
    val normalized = message?.lowercase().orEmpty()
    return listOf(
        "timeout",
        "timed out",
        "connection was lost",
        "connection reset",
        "network connection",
        "temporarily unavailable",
    ).any(normalized::contains)
}

internal fun downloadRetryDelayMillis(retryNumber: Int): Long =
    (1_000L shl (retryNumber - 1).coerceIn(0, 3))
