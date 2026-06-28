package com.anvit.localai.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal data class ThinkingMarker(
    val start: String,
    val end: String,
)

internal val supportedThinkingMarkers = listOf(
    ThinkingMarker(start = "<|channel>thought", end = "<channel|>"),
    ThinkingMarker(start = "<think>", end = "</think>"),
)

internal class ThinkingMarkerStreamParser(
    private val markers: List<ThinkingMarker> = supportedThinkingMarkers,
) {
    private var buffer = ""
    private var activeEnd: String? = null

    fun accept(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        buffer += chunk

        val output = mutableListOf<String>()
        var advanced = true
        while (advanced) {
            advanced = if (activeEnd == null) {
                processOutsideThinking(output)
            } else {
                processInsideThinking(output, activeEnd!!)
            }
        }
        return output
    }

    fun finish(): List<String> {
        val output = mutableListOf<String>()
        if (buffer.isNotEmpty()) {
            output += buffer
            buffer = ""
        }
        if (activeEnd != null) {
            output += InferenceService.SENTINEL_ENDTHINK
            activeEnd = null
        }
        return output
    }

    private fun processOutsideThinking(output: MutableList<String>): Boolean {
        val match = findNextStart(buffer)
        if (match != null) {
            if (match.index > 0) output += buffer.substring(0, match.index)
            output += InferenceService.SENTINEL_THINK
            activeEnd = match.marker.end
            buffer = buffer
                .substring(match.index + match.marker.start.length)
                .trimStart('\n')
            return true
        }

        val partialStartLength = buffer.partialMarkerSuffixLength(markers.map { it.start })
        return flushSafePrefix(reservedSuffixLength = partialStartLength, output = output)
    }

    private fun processInsideThinking(output: MutableList<String>, endMarker: String): Boolean {
        val endIndex = buffer.indexOf(endMarker)
        if (endIndex >= 0) {
            if (endIndex > 0) output += buffer.substring(0, endIndex)
            output += InferenceService.SENTINEL_ENDTHINK
            activeEnd = null
            buffer = buffer
                .substring(endIndex + endMarker.length)
                .trimStart('\n')
            return true
        }

        val partialEndLength = buffer.partialMarkerSuffixLength(listOf(endMarker))
        return flushSafePrefix(reservedSuffixLength = partialEndLength, output = output)
    }

    private fun flushSafePrefix(reservedSuffixLength: Int, output: MutableList<String>): Boolean {
        val safeLength = buffer.length - reservedSuffixLength
        if (safeLength <= 0) return false
        output += buffer.substring(0, safeLength)
        buffer = buffer.substring(safeLength)
        return true
    }

    private fun findNextStart(text: String): ThinkingMarkerMatch? =
        markers
            .mapNotNull { marker ->
                val index = text.indexOf(marker.start)
                if (index >= 0) ThinkingMarkerMatch(index, marker) else null
            }
            .minByOrNull { it.index }

    private data class ThinkingMarkerMatch(
        val index: Int,
        val marker: ThinkingMarker,
    )
}

private fun String.partialMarkerSuffixLength(markers: List<String>): Int =
    markers.maxOf { marker ->
        val maxLength = minOf(length, marker.length - 1)
        (maxLength downTo 1).firstOrNull { candidateLength ->
            marker.startsWith(takeLast(candidateLength))
        } ?: 0
    }

internal fun Flow<String>.parseThinkingMarkers(): Flow<String> = flow {
    val parser = ThinkingMarkerStreamParser()
    collect { chunk ->
        parser.accept(chunk).forEach { emit(it) }
    }
    parser.finish().forEach { emit(it) }
}

internal fun stripThinkingMarkersFromText(text: String): String {
    val parser = ThinkingMarkerStreamParser()
    val parsed = parser.accept(text) + parser.finish()
    val stripped = StringBuilder()
    var inThinking = false

    parsed.forEach { part ->
        when (part) {
            InferenceService.SENTINEL_THINK -> inThinking = true
            InferenceService.SENTINEL_ENDTHINK -> inThinking = false
            else -> if (!inThinking) stripped.appendOutsideThinking(part)
        }
    }

    return stripped.toString().trim()
}

private fun StringBuilder.appendOutsideThinking(part: String) {
    val text = if (
        isNotEmpty() &&
        last().isWhitespace() &&
        part.firstOrNull()?.isWhitespace() == true
    ) {
        part.dropWhile { it.isWhitespace() }
    } else {
        part
    }
    append(text)
}
