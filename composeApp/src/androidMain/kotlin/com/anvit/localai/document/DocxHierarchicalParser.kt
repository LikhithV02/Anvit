package com.anvit.localai.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.ByteArrayInputStream

class DocxHierarchicalParser : DocumentParser {

    override fun supports(fileName: String) =
        fileName.endsWith(".docx", ignoreCase = true) || fileName.endsWith(".doc", ignoreCase = true)

    override suspend fun parse(fileName: String, bytes: ByteArray): SectionNode =
        withContext(Dispatchers.IO) {
            val doc = XWPFDocument(ByteArrayInputStream(bytes))
            val rootNode = SectionNode(title = fileName, level = 0)
            val nodeStack = mutableListOf(rootNode)
            val currentListItems = mutableListOf<String>()

            fun flushList() {
                if (currentListItems.isEmpty()) return
                val text = currentListItems.joinToString("\n")
                nodeStack.last().blocks.add(
                    Block.ListBlock(currentListItems.toList(), TokenCounter.estimate(text))
                )
                currentListItems.clear()
            }

            for (element in doc.bodyElements) {
                when (element) {
                    is XWPFParagraph -> {
                        val text = element.text.trim()
                        if (text.isBlank()) continue

                        // numID is non-null when the paragraph belongs to a numbered/bulleted list
                        if (element.numID != null) {
                            currentListItems.add("- $text")
                            continue
                        } else {
                            flushList()
                        }

                        val headingLevel = getHeadingLevel(element.styleID ?: "")
                        if (headingLevel > 0) {
                            val newNode = SectionNode(title = text, level = headingLevel)
                            while (nodeStack.size > 1 && nodeStack.last().level >= headingLevel) {
                                nodeStack.removeLast()
                            }
                            nodeStack.last().children.add(newNode)
                            nodeStack.add(newNode)
                        } else {
                            nodeStack.last().blocks.add(
                                Block.Text(text, TokenCounter.estimate(text))
                            )
                        }
                    }

                    is XWPFTable -> {
                        flushList()
                        val allRows = element.rows.map { row ->
                            row.tableCells.map { it.text.replace("\n", " ").trim() }
                        }
                        val headers = allRows.firstOrNull() ?: emptyList()
                        val dataRows = if (allRows.size > 1) allRows.drop(1) else emptyList()
                        val markdown = buildMarkdownFromRows(allRows)
                        nodeStack.last().blocks.add(
                            Block.Table(markdown, headers, dataRows, TokenCounter.estimate(markdown))
                        )
                    }
                }
            }

            flushList()
            doc.close()
            rootNode
        }

    private fun getHeadingLevel(style: String): Int = when {
        style.contains("Heading1", ignoreCase = true) -> 1
        style.contains("Heading2", ignoreCase = true) -> 2
        style.contains("Heading3", ignoreCase = true) -> 3
        style.contains("Heading4", ignoreCase = true) -> 4
        else -> 0
    }

    private fun buildMarkdownFromRows(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val sb = StringBuilder()
        for ((i, row) in rows.withIndex()) {
            sb.append("| ${row.joinToString(" | ")} |\n")
            if (i == 0) sb.append("| ${row.map { "---" }.joinToString(" | ")} |\n")
        }
        return sb.toString()
    }
}
