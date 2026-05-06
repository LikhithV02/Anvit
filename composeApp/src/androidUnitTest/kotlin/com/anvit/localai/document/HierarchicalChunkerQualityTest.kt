package com.anvit.localai.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HierarchicalChunkerQualityTest {
    @Test
    fun removesRepeatedPageFurnitureAndTinyStubsButKeepsTables() {
        val root = SectionNode("Document", 0)
        repeat(5) {
            root.blocks.add(Block.Text("Page ${it + 1} of 5", 3))
            root.blocks.add(Block.Text("(₹ in crore)", 3))
            root.blocks.add(Block.Table(
                markdown = "",
                headers = listOf("Registered Office:", "Corporate Communications:", "Telephone"),
                rows = listOf(listOf("Maker Chambers IV", "Mumbai", "(+91 22) 3555 5000")),
                tokenCount = 12
            ))
        }
        root.blocks.add(Block.Table(
            markdown = "",
            headers = listOf("Segment", "Revenue", "EBITDA"),
            rows = listOf(listOf("O2C", "100", "20")),
            tokenCount = 12
        ))
        root.blocks.add(Block.Text("Jio Platforms revenue increased because subscriber additions and ARPU improved during the quarter.", 18))

        val chunks = HierarchicalChunker(maxTokensPerChunk = 8000).chunkTree(root)

        assertFalse(chunks.any { it.content.contains("Page 1 of 5") })
        assertFalse(chunks.any { it.content.contains("(₹ in crore)") })
        assertEquals(1, chunks.count { it.content.contains("Registered Office:") })
        assertTrue(chunks.any { it.content.contains("Segment: O2C") })
        assertTrue(chunks.any { it.content.contains("Jio Platforms revenue increased") })
    }
}
