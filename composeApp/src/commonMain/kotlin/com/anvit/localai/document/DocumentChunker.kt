package com.anvit.localai.document

object DocumentChunker {
    fun chunk(text: String, maxChunkSize: Int = 800, overlapSize: Int = 100): List<String> {
        if (text.length <= maxChunkSize) return listOf(text.trim()).filter { it.isNotEmpty() }
        val paragraphs = text.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        return if (paragraphs.size > 1) chunkByParagraphs(paragraphs, maxChunkSize, overlapSize)
               else chunkBySentences(text, maxChunkSize, overlapSize)
    }

    private fun chunkByParagraphs(paragraphs: List<String>, maxSize: Int, overlap: Int): List<String> {
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (para in paragraphs) {
            if (para.length > maxSize) {
                if (current.isNotEmpty()) { chunks.add(current.toString().trim()); current = StringBuilder(getOverlap(current.toString(), overlap)); if (current.isNotEmpty()) current.append("\n\n") }
                chunks.addAll(chunkBySentences(para, maxSize, overlap))
            } else if (current.length + para.length > maxSize && current.isNotEmpty()) {
                chunks.add(current.toString().trim()); current = StringBuilder(getOverlap(current.toString(), overlap)); if (current.isNotEmpty()) current.append("\n\n"); current.append(para).append("\n\n")
            } else { current.append(para).append("\n\n") }
        }
        if (current.isNotEmpty()) chunks.add(current.toString().trim())
        return chunks.filter { it.isNotEmpty() }
    }

    private fun chunkBySentences(text: String, maxSize: Int, overlap: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.trim().isNotEmpty() }
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (sentence in sentences) {
            val s = sentence.trim()
            if (s.length > maxSize) {
                if (current.isNotEmpty()) { chunks.add(current.toString().trim()); current = StringBuilder() }
                var word = StringBuilder()
                for (w in s.split(" ")) {
                    if (word.length + w.length + 1 > maxSize && word.isNotEmpty()) { chunks.add(word.toString().trim()); word = StringBuilder(getOverlap(word.toString(), overlap)); if (word.isNotEmpty()) word.append(" ") }
                    word.append(w).append(" ")
                }
                if (word.isNotEmpty()) current = word
            } else if (current.length + s.length + 2 > maxSize && current.isNotEmpty()) {
                chunks.add(current.toString().trim()); current = StringBuilder(getOverlap(current.toString(), overlap)); if (current.isNotEmpty()) current.append(" "); current.append(s).append(" ")
            } else { current.append(s).append(" ") }
        }
        if (current.isNotEmpty()) chunks.add(current.toString().trim())
        return chunks.ifEmpty { listOf(text.trim()) }.filter { it.isNotEmpty() }
    }

    private fun getOverlap(text: String, size: Int): String {
        if (text.length <= size) return text
        val sub = text.takeLast(size)
        val dotIdx = sub.lastIndexOf(". ")
        return if (dotIdx > 0) sub.substring(dotIdx + 2) else sub
    }
}
