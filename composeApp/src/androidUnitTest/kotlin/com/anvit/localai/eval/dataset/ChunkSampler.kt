package com.anvit.localai.eval.dataset

import kotlin.random.Random

class ChunkSampler(
    private val chunks: List<ChunkRecord>,
    seed: Int = 42
) {
    private val random = Random(seed)

    fun singleHop(count: Int): List<List<ChunkRecord>> =
        chunks.shuffled(random).take(count).map { listOf(it) }

    fun tableLookup(count: Int): List<List<ChunkRecord>> =
        chunks.filter { it.groupId != null || it.content.contains(" | ") || it.content.startsWith("Table") }
            .shuffled(random)
            .take(count)
            .map { listOf(it) }

    fun multiHop(count: Int): List<List<ChunkRecord>> {
        if (chunks.size < 2) return emptyList()
        val byDoc = chunks.groupBy { it.docId }.values.filter { it.isNotEmpty() }
        return buildList {
            repeat(count) {
                val selected = if (byDoc.size >= 2) {
                    byDoc.shuffled(random).take(2).map { it.random(random) }
                } else {
                    chunks.shuffled(random).take(2)
                }
                if (selected.size >= 2) add(selected)
            }
        }
    }

    fun adversarial(count: Int): List<List<ChunkRecord>> =
        List(count) { emptyList() }
}
