package com.anvit.localai.document

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ChunkerCsvTest {

    @Test
    fun testExportChunksToCsv() = runBlocking {
        val testDocsFolder = File("/Users/likhithv/AndroidStudioProjects/AgenticRAG/Test Docs")
        
        if (!testDocsFolder.exists() || !testDocsFolder.isDirectory) {
            println("Test Docs folder not found at ${testDocsFolder.absolutePath}.")
            return@runBlocking
        }

        val documentFiles = testDocsFolder.listFiles { file ->
            file.isFile && (file.name.endsWith(".docx", ignoreCase = true) || file.name.endsWith(".pdf", ignoreCase = true))
        }

        if (documentFiles.isNullOrEmpty()) {
            println("No .docx or .pdf files found in ${testDocsFolder.absolutePath}.")
            return@runBlocking
        }

        for (testFile in documentFiles) {
            val fileName = testFile.name
            println("\n--- Processing $fileName ---")
            val bytes = testFile.readBytes()
            val parser = when {
                fileName.endsWith(".docx", ignoreCase = true) -> DocxHierarchicalParser()
                fileName.endsWith(".pdf", ignoreCase = true) -> DesktopPdfHierarchicalParser()
                else -> throw IllegalArgumentException("Unsupported file type")
            }

            println("Parsing document structure...")
            val rootNode = parser.parse(fileName, bytes)

            println("Chunking document...")
            val chunker = HierarchicalChunker(maxTokensPerChunk = 8000)
            val chunks = chunker.chunkTree(rootNode)

            val tableChunks = chunks.count { it.content.lines().any { l -> l.trim().startsWith("|") } }
            val groupedChunks = chunks.count { it.groupId != null }
            println("Generated ${chunks.size} chunks ($tableChunks table chunks, $groupedChunks grouped) for $fileName. Exporting to CSV...")

            // Save CSV in the same Test Docs directory
            val csvFile = File(testDocsFolder, "chunks_export_${fileName}.csv")
            csvFile.bufferedWriter().use { writer ->
                writer.write("Chunk_ID,Token_Count,Group_ID,Is_Group_Head,Hierarchy_Path,Content\n")

                for (chunk in chunks) {
                    val id = escapeCsv(chunk.id)
                    val tokenCount = chunk.tokenCount.toString()
                    val groupId = escapeCsv(chunk.groupId ?: "")
                    val isHead = chunk.isGroupHead.toString()
                    val hierarchy = escapeCsv(chunk.hierarchyPath.joinToString(" > "))
                    val content = escapeCsv(chunk.content)

                    writer.write("$id,$tokenCount,$groupId,$isHead,$hierarchy,$content\n")
                }
            }
            
            println("Export complete: ${csvFile.absolutePath}")
            assertTrue(csvFile.exists(), "CSV file should be created for $fileName")
        }
    }

    @Test
    fun testExportChunksToMarkdown() = runBlocking {
        val testDocsFolder = File("/Users/likhithv/AndroidStudioProjects/AgenticRAG/Test Docs")

        if (!testDocsFolder.exists() || !testDocsFolder.isDirectory) {
            println("Test Docs folder not found at ${testDocsFolder.absolutePath}.")
            return@runBlocking
        }

        val documentFiles = testDocsFolder.listFiles { file ->
            file.isFile && (file.name.endsWith(".docx", ignoreCase = true) || file.name.endsWith(".pdf", ignoreCase = true))
        }

        if (documentFiles.isNullOrEmpty()) {
            println("No .docx or .pdf files found in ${testDocsFolder.absolutePath}.")
            return@runBlocking
        }

        for (testFile in documentFiles) {
            val fileName = testFile.name
            println("\n--- Processing $fileName ---")
            val bytes = testFile.readBytes()
            val parser = when {
                fileName.endsWith(".docx", ignoreCase = true) -> DocxHierarchicalParser()
                fileName.endsWith(".pdf", ignoreCase = true) -> DesktopPdfHierarchicalParser()
                else -> throw IllegalArgumentException("Unsupported file type")
            }

            val rootNode = parser.parse(fileName, bytes)
            val chunks = HierarchicalChunker(maxTokensPerChunk = 8000).chunkTree(rootNode)

            val mdFile = File(testDocsFolder, "chunks_export_${fileName}.md")
            mdFile.bufferedWriter().use { writer ->
                writer.write("# Chunks: $fileName\n")
                writer.write("Total: ${chunks.size} chunks\n\n")

                chunks.forEachIndexed { index, chunk ->
                    val path = chunk.hierarchyPath.joinToString(" > ")
                    writer.write("---\n\n")
                    writer.write("**Chunk ${index + 1}** · ${chunk.tokenCount} tokens")
                    if (path.isNotBlank()) writer.write(" · $path")
                    writer.write("\n\n")
                    writer.write(chunk.content)
                    writer.write("\n\n")
                }
            }

            println("Markdown export: ${mdFile.absolutePath}")
            assertTrue(mdFile.exists(), "Markdown file should be created for $fileName")
        }
    }

    private fun escapeCsv(value: String): String {
        var escaped = value.replace("\"", "\"\"")
        if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\"")) {
            escaped = "\"$escaped\""
        }
        return escaped
    }
}
