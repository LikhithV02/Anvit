package com.anvit.localai.utils

import com.anvit.localai.retrieval.FtsQueryBuilder

// Pure-Kotlin ByteBuffer replacement using Kotlin stdlib

fun FloatArray.toByteArray(): ByteArray {
    val result = ByteArray(size * 4)
    forEachIndexed { i, f ->
        val bits = f.toBits()
        result[i * 4 + 0] = (bits and 0xFF).toByte()
        result[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
        result[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
        result[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
    }
    return result
}

fun ByteArray.toFloatArray(): FloatArray {
    val result = FloatArray(size / 4)
    for (i in result.indices) {
        val bits = ((this[i * 4 + 3].toInt() and 0xFF) shl 24) or
                   ((this[i * 4 + 2].toInt() and 0xFF) shl 16) or
                   ((this[i * 4 + 1].toInt() and 0xFF) shl 8) or
                    (this[i * 4 + 0].toInt() and 0xFF)
        result[i] = Float.fromBits(bits)
    }
    return result
}

fun String.sanitizeForFts(): String = FtsQueryBuilder.build(this)
