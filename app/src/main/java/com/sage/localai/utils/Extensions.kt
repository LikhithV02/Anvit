package com.sage.localai.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun FloatArray.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buffer.putFloat(it) }
    return buffer.array()
}

fun ByteArray.toFloatArray(): FloatArray {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buffer.float }
}

fun String.sanitizeForFts(): String {
    // Escape FTS5 special characters and wrap in quotes for exact phrase search
    return this.replace("\"", "\"\"")
        .replace("*", "")
        .replace("(", "")
        .replace(")", "")
        .trim()
}
