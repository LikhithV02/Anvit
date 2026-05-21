package com.anvit.localai.document

sealed class Block {
    abstract val tokenCount: Int

    data class Text(val content: String, override val tokenCount: Int) : Block()
    data class Table(
        val markdown: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        override val tokenCount: Int
    ) : Block()
    data class ListBlock(val items: List<String>, override val tokenCount: Int) : Block()
}

data class SectionNode(
    val title: String,
    val level: Int,
    val blocks: MutableList<Block> = mutableListOf(),
    val children: MutableList<SectionNode> = mutableListOf()
)

data class DocumentChunk(
    val id: String,
    val content: String,
    val hierarchyPath: List<String>,
    val tokenCount: Int,
    val groupId: String? = null,
    val isGroupHead: Boolean = false,
    val chunkType: String = ChunkTypes.TEXT,
    val parentChunkId: String? = null,
    val sectionId: String? = null,
    val pageStart: Int = 0,
    val pageEnd: Int = 0,
    val bboxJson: String = "",
    val rowRangeJson: String = ""
)

object ChunkTypes {
    const val SECTION = "SECTION"
    const val TEXT = "TEXT"
    const val TABLE = "TABLE"
    const val TABLE_PART = "TABLE_PART"
}
