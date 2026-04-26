package com.anvit.localai.utils

/** Current epoch time in milliseconds. */
expect fun currentTimeMillis(): Long

/** Random UUID string (e.g., "550e8400-e29b-41d4-a716-446655440000"). */
expect fun randomUUID(): String

/** True on iOS (used to filter platform-specific model lists). */
expect fun isIosPlatform(): Boolean
