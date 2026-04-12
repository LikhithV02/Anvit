package com.sage.localai.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sage.localai.SageApplication
import com.sage.localai.data.db.entities.CollectionEntity
import com.sage.localai.data.db.entities.DocumentEntity
import com.sage.localai.data.preferences.SagePreferences
import com.sage.localai.document.DocumentIngestionService
import com.sage.localai.document.IngestionResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class DocumentsUiState(
    val documents: List<DocumentEntity> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val activeCollectionId: String = SagePreferences.DEFAULT_COLLECTION_ID,
    val activeCollectionName: String = "General",
    val isIngesting: Boolean = false,
    val ingestionProgress: String = "",
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class DocumentsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SageApplication
    private val documentDao    = app.database.documentDao()
    private val collectionDao  = app.database.collectionDao()

    private val ingestionService = DocumentIngestionService(
        application, documentDao, app.embeddingService
    )

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { ensureDefaultCollection() }

        // Observe all collections
        viewModelScope.launch {
            collectionDao.getAllCollections().collect { collections ->
                _uiState.update { it.copy(collections = collections) }
            }
        }

        // Switch document list whenever active collection changes
        viewModelScope.launch {
            app.preferences.activeCollectionId.flatMapLatest { collectionId ->
                val collectionName = collectionDao.getCollection(collectionId)?.name ?: "General"
                _uiState.update { it.copy(activeCollectionId = collectionId, activeCollectionName = collectionName) }
                documentDao.getDocumentsForCollection(collectionId)
            }.collect { docs ->
                _uiState.update { it.copy(documents = docs) }
            }
        }
    }

    // ── Collection management ─────────────────────────────────────────────────

    private suspend fun ensureDefaultCollection() {
        val collections = collectionDao.getAllCollectionsList()
        if (collections.none { it.id == SagePreferences.DEFAULT_COLLECTION_ID }) {
            collectionDao.insertCollection(
                CollectionEntity(
                    id = SagePreferences.DEFAULT_COLLECTION_ID,
                    name = "General",
                    description = "Default document collection",
                    isDefault = true
                )
            )
        }
    }

    fun setActiveCollection(collectionId: String) {
        viewModelScope.launch {
            app.preferences.setActiveCollectionId(collectionId)
            val name = collectionDao.getCollection(collectionId)?.name ?: "General"
            _uiState.update { it.copy(activeCollectionId = collectionId, activeCollectionName = name) }
        }
    }

    fun createCollection(name: String, description: String = "") {
        if (name.isBlank()) return
        viewModelScope.launch {
            val collection = CollectionEntity(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                description = description.trim()
            )
            collectionDao.insertCollection(collection)
            // Automatically switch to the newly created collection
            setActiveCollection(collection.id)
        }
    }

    fun renameCollection(collectionId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            val collection = collectionDao.getCollection(collectionId) ?: return@launch
            collectionDao.updateCollection(collection.copy(name = newName.trim()))
            if (collectionId == _uiState.value.activeCollectionId) {
                _uiState.update { it.copy(activeCollectionName = newName.trim()) }
            }
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            // Delete all documents in the collection (chunks cascade via FK)
            val docs = documentDao.getDocumentsForCollection(collectionId).first()
            docs.forEach { ingestionService.deleteDocument(it.id) }
            collectionDao.deleteCollection(collectionId)

            // Switch to default if we just deleted the active collection
            if (collectionId == _uiState.value.activeCollectionId) {
                setActiveCollection(SagePreferences.DEFAULT_COLLECTION_ID)
            }
        }
    }

    // ── Document management ───────────────────────────────────────────────────

    fun ingestPdf(uri: Uri, fileName: String) {
        if (_uiState.value.isIngesting) return
        val collectionId = _uiState.value.activeCollectionId
        viewModelScope.launch {
            _uiState.update { it.copy(isIngesting = true, errorMessage = null, successMessage = null) }
            val result = ingestionService.ingestPdf(uri, fileName, collectionId) { progress ->
                _uiState.update { it.copy(ingestionProgress = progress) }
            }
            when (result) {
                is IngestionResult.Success -> _uiState.update {
                    it.copy(
                        isIngesting = false,
                        ingestionProgress = "",
                        successMessage = "Added \"$fileName\" (${result.chunkCount} chunks, ${result.pageCount} pages)"
                    )
                }
                is IngestionResult.Error -> _uiState.update {
                    it.copy(isIngesting = false, ingestionProgress = "", errorMessage = result.message)
                }
            }
        }
    }

    fun deleteDocument(doc: DocumentEntity) {
        viewModelScope.launch { ingestionService.deleteDocument(doc.id) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    fun documentCountForCollection(collectionId: String): Int =
        _uiState.value.documents.count { it.collectionId == collectionId }
}
