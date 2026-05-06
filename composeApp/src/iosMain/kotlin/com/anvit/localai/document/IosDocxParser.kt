package com.anvit.localai.document

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSXMLParser
import platform.Foundation.NSXMLParserDelegateProtocol
import platform.Foundation.create
import platform.darwin.NSObject

class IosDocxParser : DocumentParser {

    override fun supports(fileName: String) =
        fileName.endsWith(".docx", ignoreCase = true)

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun parse(fileName: String, bytes: ByteArray): SectionNode =
        withContext(Dispatchers.Default) {
            val entry = findZipEntry(bytes, "word/document.xml")
                ?: return@withContext SectionNode(title = fileName, level = 0)

            val xmlBytes = when (entry.method) {
                0    -> entry.data
                8    -> inflateRaw(entry.data, entry.uncompressedSize)
                          ?: return@withContext SectionNode(title = fileName, level = 0)
                else -> return@withContext SectionNode(title = fileName, level = 0)
            }

            val nsData = xmlBytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = xmlBytes.size.toULong())
            }
            val parser = NSXMLParser(data = nsData)
            val delegate = DocxXmlDelegate()
            parser.setDelegate(delegate)
            parser.parse()
            delegate.complete()
            delegate.rootNode
        }
}

// ── ZIP reader (pure Kotlin) ──────────────────────────────────────────────────

private data class ZipEntry(
    val name: String,
    val method: Int,
    val data: ByteArray,
    val uncompressedSize: Int
)

private fun findZipEntry(zip: ByteArray, targetPath: String): ZipEntry? {
    var i = 0
    while (i <= zip.size - 30) {
        if (zip[i] != 0x50.toByte() || zip[i + 1] != 0x4B.toByte() ||
            zip[i + 2] != 0x03.toByte() || zip[i + 3] != 0x04.toByte()
        ) { i++; continue }

        val method         = zip.readUShortLE(i + 8).toInt()
        val compSize       = zip.readUIntLE(i + 18).toInt()
        val uncompSize     = zip.readUIntLE(i + 22).toInt()
        val nameLen        = zip.readUShortLE(i + 26).toInt()
        val extraLen       = zip.readUShortLE(i + 28).toInt()
        val dataStart      = i + 30 + nameLen + extraLen
        val entryName      = zip.copyOfRange(i + 30, i + 30 + nameLen).decodeToString()

        if (entryName == targetPath && dataStart + compSize <= zip.size) {
            return ZipEntry(entryName, method, zip.copyOfRange(dataStart, dataStart + compSize), uncompSize)
        }
        i = dataStart + compSize.coerceAtLeast(0)
    }
    return null
}

private fun ByteArray.readUShortLE(offset: Int): UShort =
    (((this[offset + 1].toInt() and 0xFF) shl 8) or (this[offset].toInt() and 0xFF)).toUShort()

private fun ByteArray.readUIntLE(offset: Int): UInt =
    (((this[offset + 3].toInt() and 0xFF) shl 24) or
     ((this[offset + 2].toInt() and 0xFF) shl 16) or
     ((this[offset + 1].toInt() and 0xFF) shl 8)  or
      (this[offset    ].toInt() and 0xFF)).toUInt()

// ── NSXMLParser SAX delegate ──────────────────────────────────────────────────

private class DocxXmlDelegate : NSObject(), NSXMLParserDelegateProtocol {

    val rootNode = SectionNode(title = "Document", level = 0)
    private val nodeStack = mutableListOf(rootNode)
    private val currentListItems = mutableListOf<String>()

    private var currentStyle = ""
    private var isListItem = false
    private var insideParagraph = false
    private var insideRun = false
    private val textBuffer = StringBuilder()

    private var insideTable = false
    private val tableRows = mutableListOf<List<String>>()
    private val currentRow = mutableListOf<String>()
    private val cellBuffer = StringBuilder()
    private var insideCell = false

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun parser(
        parser: NSXMLParser,
        didStartElement: String,
        namespaceURI: String?,
        qualifiedName: String?,
        attributes: Map<Any?, *>
    ) {
        when (didStartElement) {
            "w:p"     -> {
                insideParagraph = true
                currentStyle = ""; isListItem = false
                textBuffer.clear()
            }
            "w:pStyle" -> currentStyle = attributes["w:val"] as? String ?: ""
            "w:numPr"  -> isListItem = true
            "w:t"      -> insideRun = true
            "w:tbl"    -> { insideTable = true; tableRows.clear() }
            "w:tr"     -> currentRow.clear()
            "w:tc"     -> { insideCell = true; cellBuffer.clear() }
        }
    }

    override fun parser(parser: NSXMLParser, foundCharacters: String) {
        when {
            insideRun && insideParagraph -> textBuffer.append(foundCharacters)
            insideCell                   -> cellBuffer.append(foundCharacters)
        }
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun parser(
        parser: NSXMLParser,
        didEndElement: String,
        namespaceURI: String?,
        qualifiedName: String?
    ) {
        when (didEndElement) {
            "w:t"  -> insideRun = false
            "w:tc" -> {
                currentRow.add(cellBuffer.toString().replace("\n", " ").trim())
                insideCell = false
            }
            "w:tr" -> tableRows.add(currentRow.toList())
            "w:tbl" -> {
                flushList()
                val headers = tableRows.firstOrNull() ?: emptyList()
                val dataRows = if (tableRows.size > 1) tableRows.drop(1) else emptyList()
                val md = buildTableMarkdown(tableRows)
                nodeStack.last().blocks.add(Block.Table(md, headers, dataRows, TokenCounter.estimate(md)))
                insideTable = false
            }
            "w:p"  -> {
                insideParagraph = false
                val text = textBuffer.toString().trim()
                if (text.isBlank()) return

                if (isListItem) {
                    currentListItems.add("- $text")
                    return
                }
                flushList()

                val level = getHeadingLevel(currentStyle)
                if (level > 0) {
                    val newNode = SectionNode(title = text, level = level)
                    while (nodeStack.size > 1 && nodeStack.last().level >= level) {
                        nodeStack.removeLast()
                    }
                    nodeStack.last().children.add(newNode)
                    nodeStack.add(newNode)
                } else {
                    nodeStack.last().blocks.add(Block.Text(text, TokenCounter.estimate(text)))
                }
            }
        }
    }

    fun complete() { flushList() }

    private fun flushList() {
        if (currentListItems.isEmpty()) return
        val text = currentListItems.joinToString("\n")
        nodeStack.last().blocks.add(
            Block.ListBlock(currentListItems.toList(), TokenCounter.estimate(text))
        )
        currentListItems.clear()
    }

    private fun getHeadingLevel(style: String) = when {
        style.contains("Heading1", ignoreCase = true) -> 1
        style.contains("Heading2", ignoreCase = true) -> 2
        style.contains("Heading3", ignoreCase = true) -> 3
        style.contains("Heading4", ignoreCase = true) -> 4
        else -> 0
    }

    private fun buildTableMarkdown(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val sb = StringBuilder()
        for ((i, row) in rows.withIndex()) {
            sb.append("| ${row.joinToString(" | ")} |\n")
            if (i == 0) sb.append("| ${row.map { "---" }.joinToString(" | ")} |\n")
        }
        return sb.toString()
    }
}
