package com.anvit.localai.document

import com.anvit.localai.data.db.entities.ChunkEntity
import com.anvit.localai.retrieval.RetrievedChunk

private const val PATH_SEPARATOR = "§"

fun DocumentChunk.toChunkEntity(
    docId: String,
    fileName: String,
    index: Int,
    embedding: ByteArray?,
    collectionId: String
): ChunkEntity = ChunkEntity(
    id = "${docId}_$index",
    docId = docId,
    fileName = fileName,
    chunkIndex = index,
    content = content,
    embedding = embedding,
    collectionId = collectionId,
    hierarchyPath = hierarchyPath.joinToString(PATH_SEPARATOR),
    groupId = groupId,
    isGroupHead = isGroupHead,
    chunkType = chunkType,
    parentChunkId = parentChunkId,
    sectionId = sectionId,
    pageStart = pageStart,
    pageEnd = pageEnd,
    bboxJson = bboxJson,
    rowRangeJson = rowRangeJson
)

fun ChunkEntity.hierarchyPathList(): List<String> =
    if (hierarchyPath.isBlank()) emptyList()
    else hierarchyPath.split(PATH_SEPARATOR)

fun ChunkEntity.toRetrievedChunk(score: Float = 0f): RetrievedChunk =
    RetrievedChunk(
        chunkId = id,
        docId = docId,
        fileName = fileName,
        chunkIndex = chunkIndex,
        content = content,
        score = score,
        vectorScore = score,
        bm25Rank = 0,
        groupId = groupId,
        lexicalScore = 0f,
        retrievalSource = "group_expansion"
    )
