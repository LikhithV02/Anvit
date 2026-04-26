package com.anvit.localai.utils

import java.util.UUID

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun randomUUID(): String = UUID.randomUUID().toString()

actual fun isIosPlatform(): Boolean = false
