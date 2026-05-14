package com.anvit.localai.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvit.localai.data.db.CollectionDao
import com.anvit.localai.data.db.DocumentDao
import com.anvit.localai.data.db.entities.CollectionEntity
import com.anvit.localai.data.db.entities.DocumentEntity
import com.anvit.localai.data.preferences.AnvitPreferences
import com.anvit.localai.document.DocumentIngestionService
import com.anvit.localai.document.IngestionResult
import com.anvit.localai.utils.currentTimeMillis
import com.anvit.localai.utils.randomUUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DocumentsUiState(
    val documents: List<DocumentEntity> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val activeCollectionId: String = AnvitPreferences.DEFAULT_COLLECTION_ID,
    val activeCollectionName: String = "General",
    val isIngesting: Boolean = false,
    val ingestionProgress: String = "",
    val ingestionProgressFraction: Float = 0f,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class DocumentsViewModel(
    private val preferences: AnvitPreferences,
    private val documentDao: DocumentDao,
    private val collectionDao: CollectionDao,
    private val ingestionService: DocumentIngestionService,
    private val downloadService: com.anvit.localai.download.DownloadService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()
    private var ingestionJob: Job? = null

    init {
        viewModelScope.launch { ensureDefaultCollection() }
        viewModelScope.launch {
            collectionDao.getAllCollections().collect { _uiState.update { s -> s.copy(collections = it) } }
        }
        viewModelScope.launch {
            preferences.activeCollectionId.flatMapLatest { collectionId ->
                val name = collectionDao.getCollection(collectionId)?.name ?: "General"
                _uiState.update { s -> s.copy(activeCollectionId = collectionId, activeCollectionName = name) }
                documentDao.getDocumentsForCollection(collectionId)
            }.collect { docs -> _uiState.update { s -> s.copy(documents = docs) } }
        }
    }

    private suspend fun ensureDefaultCollection() {
        val collections = collectionDao.getAllCollectionsList()
        if (collections.none { it.id == AnvitPreferences.DEFAULT_COLLECTION_ID }) {
            collectionDao.insertCollection(
                CollectionEntity(
                    AnvitPreferences.DEFAULT_COLLECTION_ID, "General",
                    "Default document collection", currentTimeMillis(), true
                )
            )
        }
    }

    fun setActiveCollection(id: String) {
        viewModelScope.launch {
            preferences.setActiveCollectionId(id)
            val name = collectionDao.getCollection(id)?.name ?: "General"
            _uiState.update { s -> s.copy(activeCollectionId = id, activeCollectionName = name) }
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

    fun renameCollection(id: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            val c = collectionDao.getCollection(id) ?: return@launch
            collectionDao.updateCollection(c.copy(name = newName.trim()))
            if (id == _uiState.value.activeCollectionId) _uiState.update { s -> s.copy(activeCollectionName = newName.trim()) }
        }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch {
            val docs = documentDao.getDocumentsForCollection(id).first()
            docs.forEach { ingestionService.deleteDocument(it.id) }
            collectionDao.deleteCollection(id)
            if (id == _uiState.value.activeCollectionId) setActiveCollection(AnvitPreferences.DEFAULT_COLLECTION_ID)
        }
    }

    /** Ingest a document from raw bytes (platform picks the file and reads bytes). */
    fun ingestDocument(fileName: String, documentBytes: ByteArray) {
        if (_uiState.value.isIngesting) return
        val collectionId = _uiState.value.activeCollectionId
        ingestionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isIngesting = true,
                    ingestionProgress = "Preparing $fileName",
                    ingestionProgressFraction = 0f,
                    errorMessage = null,
                    successMessage = null
                )
            }
            val result = ingestionService.ingestDocument(fileName, documentBytes, collectionId) { fraction, progress ->
                _uiState.update {
                    it.copy(
                        ingestionProgress = progress,
                        ingestionProgressFraction = fraction.coerceIn(0f, 1f)
                    )
                }
            }
            when (result) {
                is IngestionResult.Success -> _uiState.update {
                    if (!it.isIngesting) it else it.copy(isIngesting = false, ingestionProgress = "", ingestionProgressFraction = 0f,
                        successMessage = "Added \"$fileName\" (${result.chunkCount} chunks, ${result.pageCount} pages)")
                }
                is IngestionResult.Error   -> _uiState.update {
                    if (!it.isIngesting) it else it.copy(isIngesting = false, ingestionProgress = "", ingestionProgressFraction = 0f, errorMessage = result.message)
                }
                IngestionResult.Cancelled -> _uiState.update {
                    if (!it.isIngesting) it else it.copy(isIngesting = false, ingestionProgress = "", ingestionProgressFraction = 0f, successMessage = "Indexing cancelled")
                }
            }
            ingestionJob = null
        }
    }

    fun cancelIngestion() {
        val job = ingestionJob ?: return
        ingestionJob = null
        job.cancel()
        _uiState.update {
            it.copy(isIngesting = false, ingestionProgress = "", ingestionProgressFraction = 0f, successMessage = "Indexing cancelled")
        }
        viewModelScope.launch {
            ingestionService.cancelActiveIngestion()
        }
    }

    fun deleteDocument(doc: DocumentEntity) {
        viewModelScope.launch { ingestionService.deleteDocument(doc.id) }
    }

    fun clearMessages() { _uiState.update { it.copy(errorMessage = null, successMessage = null) } }
}
