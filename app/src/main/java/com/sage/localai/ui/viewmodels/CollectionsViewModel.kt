package com.sage.localai.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sage.localai.SageApplication
import com.sage.localai.data.db.entities.CollectionEntity
import com.sage.localai.data.preferences.SagePreferences
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class CollectionsUiState(
    val collections: List<CollectionEntity> = emptyList(),
    val activeCollectionId: String = SagePreferences.DEFAULT_COLLECTION_ID,
    val documentCountsById: Map<String, Int> = emptyMap()
)

/**
 * Standalone ViewModel for managing vector-DB collections.
 * The full document-aware version lives in [DocumentsViewModel]; this VM is a
 * lightweight alternative for screens that only need to browse / switch collections
 * without document ingestion logic.
 */
class CollectionsViewModel(application: Application) : AndroidViewModel(application) {

    private val app           = application as SageApplication
    private val collectionDao = app.database.collectionDao()
    private val documentDao   = app.database.documentDao()

    private val _uiState = MutableStateFlow(CollectionsUiState())
    val uiState: StateFlow<CollectionsUiState> = _uiState.asStateFlow()

    init {
        // Observe all collections
        viewModelScope.launch {
            collectionDao.getAllCollections().collect { collections ->
                _uiState.update { it.copy(collections = collections) }
                refreshDocumentCounts(collections)
            }
        }

        // Observe active collection
        viewModelScope.launch {
            app.preferences.activeCollectionId.collect { id ->
                _uiState.update { it.copy(activeCollectionId = id) }
            }
        }
    }

    // ── Collection management ─────────────────────────────────────────────────

    fun createCollection(name: String, description: String = "") {
        if (name.isBlank()) return
        viewModelScope.launch {
            val collection = CollectionEntity(
                id          = UUID.randomUUID().toString(),
                name        = name.trim(),
                description = description.trim()
            )
            collectionDao.insertCollection(collection)
            setActiveCollection(collection.id)
        }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch {
            // Cascade: delete all documents (and their chunks) in the collection
            documentDao.deleteDocumentsForCollection(id)
            collectionDao.deleteCollection(id)
            if (id == _uiState.value.activeCollectionId) {
                setActiveCollection(SagePreferences.DEFAULT_COLLECTION_ID)
            }
        }
    }

    fun renameCollection(id: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            val collection = collectionDao.getCollection(id) ?: return@launch
            collectionDao.updateCollection(collection.copy(name = newName.trim()))
        }
    }

    fun setActiveCollection(id: String) {
        viewModelScope.launch {
            app.preferences.setActiveCollectionId(id)
        }
    }

    /** Returns a [Flow] that emits the live document count for a given collection. */
    fun getDocumentCountForCollection(collectionId: String): Flow<Int> =
        documentDao.getDocumentsForCollection(collectionId).map { it.size }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun refreshDocumentCounts(collections: List<CollectionEntity>) {
        val counts = collections.associate { c ->
            c.id to documentDao.getChunkCountForCollection(c.id)
        }
        _uiState.update { it.copy(documentCountsById = counts) }
    }
}
