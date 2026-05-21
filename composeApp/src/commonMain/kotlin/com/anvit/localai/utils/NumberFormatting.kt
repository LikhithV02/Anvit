package com.anvit.localai.utils

import kotlin.math.abs
import kotlin.math.round

fun formatFixed(value: Double, decimals: Int): String {
    require(decimals >= 0) { "decimals must be non-negative" }
    if (decimals == 0) return round(value).toLong().toString()

    val factor = (1..decimals).fold(1L) { acc, _ -> acc * 10L }
    val scaled = round(abs(value) * factor).toLong()
    val whole = scaled / factor
    val fraction = (scaled % factor).toString().padStart(decimals, '0')
    val sign = if (value < 0.0 && scaled != 0L) "-" else ""
    return "$sign$whole.$fraction"
}
