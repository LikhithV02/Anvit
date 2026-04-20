package com.anvit.localai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anvit.localai.utils.currentTimeMillis

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Long = currentTimeMillis(),
    val isDefault: Boolean = false
)
