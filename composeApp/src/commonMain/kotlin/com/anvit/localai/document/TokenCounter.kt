package com.anvit.localai.document

object TokenCounter {
    fun estimate(text: String): Int =
        (text.split(Regex("\\s+")).size * 1.3).toInt().coerceAtLeast(1)
}
