package com.sage.localai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isDefault: Boolean = false
)
