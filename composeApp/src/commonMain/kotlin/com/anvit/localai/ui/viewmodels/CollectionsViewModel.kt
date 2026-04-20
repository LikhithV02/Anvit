package com.anvit.localai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvit.localai.data.db.CollectionDao
import com.anvit.localai.data.db.DocumentDao
import com.anvit.localai.data.db.entities.CollectionEntity
import com.anvit.localai.data.preferences.AnvitPreferences
import com.anvit.localai.utils.currentTimeMillis
import com.anvit.localai.utils.randomUUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class CollectionsUiState(
    val collections: List<CollectionEntity> = emptyList(),
    val activeCollectionId: String = AnvitPreferences.DEFAULT_COLLECTION_ID,
    val documentCountsById: Map<String, Int> = emptyMap()
)

class CollectionsViewModel(
    private val preferences: AnvitPreferences,
    private val collectionDao: CollectionDao,
    private val documentDao: DocumentDao
) : ViewModel() {
    private val _uiState = MutableStateFlow(CollectionsUiState())
    val uiState: StateFlow<CollectionsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            collectionDao.getAllCollections().collect { cols ->
                _uiState.update { it.copy(collections = cols) }
                refreshDocumentCounts(cols)
            }
        }
        viewModelScope.launch {
            preferences.activeCollectionId.collect { id -> _uiState.update { it.copy(activeCollectionId = id) } }
        }
    }

    fun createCollection(name: String, description: String = "") {
        if (name.isBlank()) return
        viewModelScope.launch {
            val c = CollectionEntity(randomUUID(), name.trim(), description.trim(), currentTimeMillis())
            collectionDao.insertCollection(c)
            setActiveCollection(c.id)
        }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch {
            documentDao.deleteDocumentsForCollection(id)
            collectionDao.deleteCollection(id)
            if (id == _uiState.value.activeCollectionId) setActiveCollection(AnvitPreferences.DEFAULT_COLLECTION_ID)
        }
    }

    fun renameCollection(id: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            val c = collectionDao.getCollection(id) ?: return@launch
            collectionDao.updateCollection(c.copy(name = newName.trim()))
        }
    }

    fun setActiveCollection(id: String) { viewModelScope.launch { preferences.setActiveCollectionId(id) } }

    fun getDocumentCountForCollection(collectionId: String): Flow<Int> =
        documentDao.getDocumentsForCollection(collectionId).map { it.size }

    private suspend fun refreshDocumentCounts(collections: List<CollectionEntity>) {
        _uiState.update {
            it.copy(documentCountsById = collections.associate { c ->
                c.id to documentDao.getChunkCountForCollection(c.id)
            })
        }
    }
}
