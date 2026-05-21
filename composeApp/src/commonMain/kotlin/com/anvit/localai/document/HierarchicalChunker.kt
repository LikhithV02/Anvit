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

        // Empty leaf: the parser misclassified this line as a heading (e.g. bold highlight text).
        // Recover it as plain text content under the parent section.
        if (node.title.isNotBlank() && node.blocks.isEmpty() && node.children.isEmpty()) {
            val tokens = TokenCounter.estimate(node.title)
            result.add(DocumentChunk(
                id = randomUUID(),
                content = node.title,
                hierarchyPath = parentPath,
                tokenCount = tokens
            ))
            return
        }

        var currentContent = StringBuilder()
        var currentTokens = 0

        fun flush() {
            if (currentTokens > 0) {
                result.add(DocumentChunk(
                    id = randomUUID(),
                    content = currentContent.toString().trim(),
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
        val colCount = maxOf(table.headers.size, table.rows.firstOrNull()?.size ?: 0)
        val headers = normalizeHeaders(table.headers, colCount)

        if (colCount == 0) return

        val headerRow = "| ${headers.joinToString(" | ")} |"
        val separatorRow = "| ${headers.joinToString(" | ") { "---" }} |"

        if (table.rows.isEmpty()) {
            val content = "$headerRow\n$separatorRow"
            result.add(DocumentChunk(randomUUID(), content.trim(), path, TokenCounter.estimate(content)))
            return
        }

        val rowLines = table.rows.map { formatRowAsMarkdown(headers, it) }
        val fullContent = "$headerRow\n$separatorRow\n${rowLines.joinToString("\n")}"
        val totalTokens = TokenCounter.estimate(fullContent)

        if (totalTokens <= maxTokensPerChunk) {
            result.add(DocumentChunk(randomUUID(), fullContent.trim(), path, totalTokens))
            return
        }

        // Table exceeds limit — grouped child chunks, each with the header rows repeated
        val groupId = randomUUID()

        val headContent = "$headerRow\n$separatorRow"
        result.add(DocumentChunk(
            id = randomUUID(), content = headContent.trim(), hierarchyPath = path,
            tokenCount = TokenCounter.estimate(headContent),
            groupId = groupId, isGroupHead = true
        ))

        val rowBuffer = StringBuilder()
        var bufTokens = 0

        fun flushRowBuffer() {
            if (bufTokens == 0) return
            val content = "$headerRow\n$separatorRow\n$rowBuffer"
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
        val groupId = randomUUID()
        val itemBuffer = StringBuilder()
        var bufTokens = 0
        var isFirst = true

        fun flushItemBuffer() {
            if (bufTokens == 0) return
            result.add(DocumentChunk(
                id = randomUUID(), content = itemBuffer.toString().trim(), hierarchyPath = path,
                tokenCount = TokenCounter.estimate(itemBuffer.toString()),
                groupId = groupId, isGroupHead = isFirst
            ))
            itemBuffer.clear()
            bufTokens = 0
            isFirst = false
        }

        for (item in list.items) {
            val itemTokens = TokenCounter.estimate(item)
            if (bufTokens + itemTokens > maxTokensPerChunk && bufTokens > 0) flushItemBuffer()
            itemBuffer.append(item).append("\n")
            bufTokens += itemTokens
        }
        flushItemBuffer()
    }

    private fun formatRowAsMarkdown(headers: List<String>, row: List<String>): String {
        val colCount = maxOf(headers.size, row.size)
        return "| ${(0 until colCount).joinToString(" | ") { i -> row.getOrElse(i) { "" }.ifBlank { "-" } }} |"
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

            // Merge adjacent plain-text chunks in the same section up to MAX_MERGE_TOKENS.
            // Tables (start with |) and grouped list chunks (groupId != null) stay separate.
            val isMergeCandidate = chunk.groupId == null && !isStructuredChunk(chunk)
            if (isMergeCandidate && cleaned.isNotEmpty()) {
                val previous = cleaned.last()
                if (previous.groupId == null &&
                    !isStructuredChunk(previous) &&
                    previous.hierarchyPath == chunk.hierarchyPath &&
                    previous.tokenCount + chunk.tokenCount <= MAX_MERGE_TOKENS
                ) {
                    val mergedContent = "${previous.content.trim()}\n\n${body.trim()}".trim()
                    cleaned[cleaned.lastIndex] = previous.copy(
                        content = mergedContent,
                        tokenCount = TokenCounter.estimate(mergedContent)
                    )
                    continue
                }
            }

            cleaned.add(chunk)
        }

        return cleaned
    }

    private fun semanticBody(content: String): String = content.trim()

    private fun isStructuredChunk(chunk: DocumentChunk): Boolean =
        chunk.content.trimStart().startsWith("|") || chunk.groupId != null

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
        const val MAX_MERGE_TOKENS = 1500
        val pageFurnitureTerms = listOf(
            "registered office",
            "corporate communications",
            "telephone",
            "cin:",
            "www.ril.com"
        )
    }
}
