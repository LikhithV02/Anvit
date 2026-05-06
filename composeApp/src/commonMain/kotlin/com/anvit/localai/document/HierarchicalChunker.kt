package com.anvit.localai.document

import com.anvit.localai.utils.randomUUID

class HierarchicalChunker(private val maxTokensPerChunk: Int = 8000) {

    fun chunkTree(root: SectionNode): List<DocumentChunk> {
        val result = mutableListOf<DocumentChunk>()
        traverse(root, emptyList(), result)
        return cleanChunks(result)
    }

    private fun traverse(node: SectionNode, parentPath: List<String>, result: MutableList<DocumentChunk>) {
        val path = if (node.title.isBlank()) parentPath else parentPath + node.title

        var currentContent = StringBuilder()
        var currentTokens = 0

        fun flush() {
            if (currentTokens > 0) {
                val prefix = if (path.isNotEmpty()) path.joinToString(" > ") + "\n\n" else ""
                result.add(DocumentChunk(
                    id = randomUUID(),
                    content = (prefix + currentContent.toString()).trim(),
                    hierarchyPath = path,
                    tokenCount = currentTokens
                ))
                currentContent = StringBuilder()
                currentTokens = 0
            }
        }

        for (block in node.blocks) {
            // Tables always bypass accumulation — emit as structured key-value chunks
            if (block is Block.Table) {
                flush()
                emitTableChunks(block, path, result)
                continue
            }

            // Large lists get their own grouped chunks
            if (block is Block.ListBlock && block.tokenCount > maxTokensPerChunk) {
                flush()
                emitListChunks(block, path, result)
                continue
            }

            if (currentTokens + block.tokenCount > maxTokensPerChunk && currentTokens > 0) {
                flush()
            }

            when {
                block.tokenCount > maxTokensPerChunk -> {
                    // Only Block.Text can reach here (Table/large-List handled above)
                    if (block is Block.Text) {
                        val splits = DocumentChunker.chunk(block.content, maxTokensPerChunk * 4)
                        for (split in splits) {
                            val tokens = TokenCounter.estimate(split)
                            currentContent.append(split).append("\n\n")
                            currentTokens += tokens
                            if (currentTokens >= maxTokensPerChunk) flush()
                        }
                    }
                }
                else -> {
                    val blockContent = when (block) {
                        is Block.Text -> block.content
                        is Block.Table -> block.markdown   // unreachable; needed for exhaustive when
                        is Block.ListBlock -> block.items.joinToString("\n")
                    }
                    currentContent.append(blockContent).append("\n\n")
                    currentTokens += block.tokenCount
                }
            }
        }

        flush()

        for (child in node.children) {
            traverse(child, path, result)
        }
    }

    private fun emitTableChunks(table: Block.Table, path: List<String>, result: MutableList<DocumentChunk>) {
        val prefix = if (path.isNotEmpty()) path.joinToString(" > ") + "\n\n" else ""
        val colCount = maxOf(table.headers.size, table.rows.firstOrNull()?.size ?: 0)
        val headers = normalizeHeaders(table.headers, colCount)

        if (colCount == 0) return

        if (table.rows.isEmpty()) {
            val content = "${prefix}Table: ${headers.joinToString(", ")} (no data rows)"
            result.add(DocumentChunk(randomUUID(), content.trim(), path, TokenCounter.estimate(content)))
            return
        }

        val rowLines = table.rows.map { formatRowAsKeyValue(headers, it) }
        val fullContent = "${prefix}Table: ${headers.joinToString(", ")}\n${rowLines.joinToString("\n")}"
        val totalTokens = TokenCounter.estimate(fullContent)

        if (totalTokens <= maxTokensPerChunk) {
            result.add(DocumentChunk(randomUUID(), fullContent.trim(), path, totalTokens))
            return
        }

        // Table exceeds limit — parent summary + grouped child chunks
        val groupId = randomUUID()

        val headContent = "${prefix}Table summary: ${table.rows.size} rows. Headers: ${headers.joinToString(", ")}"
        result.add(DocumentChunk(
            id = randomUUID(), content = headContent.trim(), hierarchyPath = path,
            tokenCount = TokenCounter.estimate(headContent),
            groupId = groupId, isGroupHead = true
        ))

        val rowBuffer = StringBuilder()
        var bufTokens = 0
        val headerLine = "Table (continued) — Headers: ${headers.joinToString(", ")}"

        fun flushRowBuffer() {
            if (bufTokens == 0) return
            val content = "$prefix$headerLine\n$rowBuffer"
            result.add(DocumentChunk(
                id = randomUUID(), content = content.trim(), hierarchyPath = path,
                tokenCount = TokenCounter.estimate(content),
                groupId = groupId, isGroupHead = false
            ))
            rowBuffer.clear()
            bufTokens = 0
        }

        for (line in rowLines) {
            val lineTokens = TokenCounter.estimate(line)
            if (bufTokens + lineTokens > maxTokensPerChunk && bufTokens > 0) flushRowBuffer()
            rowBuffer.append(line).append("\n")
            bufTokens += lineTokens
        }
        flushRowBuffer()
    }

    private fun emitListChunks(list: Block.ListBlock, path: List<String>, result: MutableList<DocumentChunk>) {
        val prefix = if (path.isNotEmpty()) path.joinToString(" > ") + "\n\n" else ""
        val groupId = randomUUID()

        val firstItem = list.items.firstOrNull()?.take(80) ?: ""
        val headContent = "${prefix}List summary: ${list.items.size} items. First: $firstItem"
        result.add(DocumentChunk(
            id = randomUUID(), content = headContent.trim(), hierarchyPath = path,
            tokenCount = TokenCounter.estimate(headContent),
            groupId = groupId, isGroupHead = true
        ))

        val itemBuffer = StringBuilder()
        var bufTokens = 0

        fun flushItemBuffer() {
            if (bufTokens == 0) return
            val content = "$prefix$itemBuffer"
            result.add(DocumentChunk(
                id = randomUUID(), content = content.trim(), hierarchyPath = path,
                tokenCount = TokenCounter.estimate(content),
                groupId = groupId, isGroupHead = false
            ))
            itemBuffer.clear()
            bufTokens = 0
        }

        for (item in list.items) {
            val itemTokens = TokenCounter.estimate(item)
            if (bufTokens + itemTokens > maxTokensPerChunk && bufTokens > 0) flushItemBuffer()
            itemBuffer.append(item).append("\n")
            bufTokens += itemTokens
        }
        flushItemBuffer()
    }

    private fun formatRowAsKeyValue(headers: List<String>, row: List<String>): String {
        val colCount = maxOf(headers.size, row.size)
        val h = normalizeHeaders(headers, colCount)
        return (0 until colCount).joinToString(" | ") { i ->
            "${h[i]}: ${row.getOrElse(i) { "" }.ifEmpty { "(empty)" }}"
        }
    }

    private fun normalizeHeaders(headers: List<String>, colCount: Int): List<String> =
        List(colCount) { i -> headers.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Col ${i + 1}" }

    private fun cleanChunks(chunks: List<DocumentChunk>): List<DocumentChunk> {
        val exactSeen = mutableSetOf<String>()
        val furnitureSeen = mutableSetOf<String>()
        val cleaned = mutableListOf<DocumentChunk>()

        for (chunk in chunks) {
            val body = semanticBody(chunk.content)
            val signature = contentSignature(chunk.content)

            if (body.isBlank() || isJunkBody(body)) continue
            if (signature in exactSeen && chunk.groupId == null) continue

            val furnitureKey = pageFurnitureKey(signature)
            if (furnitureKey != null) {
                if (furnitureKey in furnitureSeen) continue
                furnitureSeen.add(furnitureKey)
            }

            exactSeen.add(signature)
            val shouldMerge = chunk.groupId == null &&
                !isStructuredTableChunk(chunk) &&
                chunk.tokenCount < MIN_TEXT_CHUNK_TOKENS

            if (shouldMerge && cleaned.isNotEmpty()) {
                val previous = cleaned.last()
                if (previous.groupId == null &&
                    !isStructuredTableChunk(previous) &&
                    previous.hierarchyPath == chunk.hierarchyPath &&
                    previous.tokenCount + chunk.tokenCount <= maxTokensPerChunk
                ) {
                    val mergedContent = "${previous.content.trim()}\n\n${body.trim()}".trim()
                    cleaned[cleaned.lastIndex] = previous.copy(
                        content = mergedContent,
                        tokenCount = TokenCounter.estimate(mergedContent)
                    )
                    continue
                }
            }

            if (shouldMerge && bodyTokenCount(body) <= TINY_TEXT_CHUNK_TOKENS) continue
            cleaned.add(chunk)
        }

        return cleaned
    }

    private fun semanticBody(content: String): String =
        content.substringAfter("\n\n", content).trim()

    private fun bodyTokenCount(body: String): Int = TokenCounter.estimate(body)

    private fun isStructuredTableChunk(chunk: DocumentChunk): Boolean {
        val body = semanticBody(chunk.content)
        return body.startsWith("Table:") ||
            body.startsWith("Table summary:") ||
            body.startsWith("Table (continued)") ||
            chunk.groupId != null
    }

    private fun isJunkBody(body: String): Boolean {
        val normalized = body.lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
        val lines = body.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isNotEmpty() && lines.all { isJunkLine(it) }) return true
        if (normalized.length <= 1) return true
        if (Regex("^page\\s+\\d+\\s+of\\s+\\d+$").matches(normalized)) return true
        if (Regex("^\\(?₹\\s*in\\s*crore\\)?$").matches(normalized)) return true
        if (Regex("^\\(?rs\\.?\\s*in\\s*crore\\)?$").matches(normalized)) return true
        if (Regex("^\\(?\\$\\s*in\\s*millions?\\)?$").matches(normalized)) return true
        return normalized in setOf("|", "-", "_", "continued")
    }

    private fun isJunkLine(line: String): Boolean {
        val normalized = line.lowercase().replace(Regex("\\s+"), " ").trim()
        return Regex("^page\\s+\\d+\\s+of\\s+\\d+$").matches(normalized) ||
            Regex("^\\(?₹\\s*in\\s*crore\\)?$").matches(normalized) ||
            Regex("^\\(?rs\\.?\\s*in\\s*crore\\)?$").matches(normalized) ||
            Regex("^\\(?\\$\\s*in\\s*millions?\\)?$").matches(normalized) ||
            normalized in setOf("|", "-", "_", "continued")
    }

    private fun contentSignature(content: String): String =
        semanticBody(content)
            .lowercase()
            .replace(Regex("page\\s+\\d+\\s+of\\s+\\d+"), "page # of #")
            .replace(Regex("\\d+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun pageFurnitureKey(signature: String): String? =
        pageFurnitureTerms.firstOrNull { signature.contains(it) }
            ?: "page".takeIf { Regex("^page # of #$").matches(signature) }

    private companion object {
        const val MIN_TEXT_CHUNK_TOKENS = 20
        const val TINY_TEXT_CHUNK_TOKENS = 5
        val pageFurnitureTerms = listOf(
            "registered office",
            "corporate communications",
            "telephone",
            "cin:",
            "www.ril.com"
        )
    }
}
