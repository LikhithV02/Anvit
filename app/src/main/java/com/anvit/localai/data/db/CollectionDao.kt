package com.anvit.localai.data.db

import androidx.room.*
import com.anvit.localai.data.db.entities.CollectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY isDefault DESC, createdAt ASC")
    fun getAllCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections ORDER BY isDefault DESC, createdAt ASC")
    suspend fun getAllCollectionsList(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getCollection(id: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: CollectionEntity)

    @Update
    suspend fun updateCollection(collection: CollectionEntity)

    // Guard: never delete the default collection
    @Query("DELETE FROM collections WHERE id = :id AND isDefault = 0")
    suspend fun deleteCollection(id: String)
}
