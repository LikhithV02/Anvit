package com.anvit.localai.utils

import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970

actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun randomUUID(): String =
    NSUUID().UUIDString()

actual fun isIosPlatform(): Boolean = true
