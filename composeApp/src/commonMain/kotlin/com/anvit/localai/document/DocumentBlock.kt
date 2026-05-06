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
    val isGroupHead: Boolean = false
)
