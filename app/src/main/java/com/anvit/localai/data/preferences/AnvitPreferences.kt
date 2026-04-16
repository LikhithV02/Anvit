package com.anvit.localai.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sage_settings")

class AnvitPreferences(private val context: Context) {

    companion object {
        const val DEFAULT_COLLECTION_ID = "default-collection"

        val SELECTED_MODEL_ID        = stringPreferencesKey("selected_model_id")
        val SELECTED_EMBEDDING_MODEL = stringPreferencesKey("selected_embedding_model")
        val ENABLE_THINKING          = booleanPreferencesKey("enable_thinking")
        val ENABLE_AGENTIC_RAG       = booleanPreferencesKey("enable_agentic_rag")
        val TEMPERATURE              = floatPreferencesKey("temperature")
        val TOP_K                    = intPreferencesKey("top_k")
        val MAX_OUTPUT_TOKENS        = intPreferencesKey("max_output_tokens")
        val MAX_RETRIEVAL_CHUNKS     = intPreferencesKey("max_retrieval_chunks")
        val ENABLE_SELF_CRITIQUE     = booleanPreferencesKey("enable_self_critique")
        val RETRIEVAL_MODE           = stringPreferencesKey("retrieval_mode")
        val HUGGINGFACE_TOKEN        = stringPreferencesKey("huggingface_token")
        val ACTIVE_SESSION_ID        = stringPreferencesKey("active_session_id")
        val ACTIVE_COLLECTION_ID     = stringPreferencesKey("active_collection_id")
        val ACCELERATOR              = stringPreferencesKey("accelerator")
    }

    private fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> =
        context.dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { it[key] ?: default }

    val selectedModelId:      Flow<String>  = flow(SELECTED_MODEL_ID,        "gemma4-e2b")
    val selectedEmbeddingModel: Flow<String> = flow(SELECTED_EMBEDDING_MODEL,  "EmbeddingGemma")
    val enableThinking:       Flow<Boolean> = flow(ENABLE_THINKING,            true)
    val enableAgenticRag:     Flow<Boolean> = flow(ENABLE_AGENTIC_RAG,         true)
    val temperature:          Flow<Float>   = flow(TEMPERATURE,                1.0f)
    val topK:                 Flow<Int>     = flow(TOP_K,                      40)
    val maxOutputTokens:      Flow<Int>     = flow(MAX_OUTPUT_TOKENS,          4000)
    val maxRetrievalChunks:   Flow<Int>     = flow(MAX_RETRIEVAL_CHUNKS,        5)
    val enableSelfCritique:   Flow<Boolean> = flow(ENABLE_SELF_CRITIQUE,        true)
    val retrievalMode:        Flow<String>  = flow(RETRIEVAL_MODE,             "hybrid")
    val huggingFaceToken:     Flow<String>  = flow(HUGGINGFACE_TOKEN,           "")
    val activeSessionId:      Flow<String>  = flow(ACTIVE_SESSION_ID,           "")
    val activeCollectionId:   Flow<String>  = flow(ACTIVE_COLLECTION_ID,        DEFAULT_COLLECTION_ID)
    val accelerator:          Flow<String>  = flow(ACCELERATOR,                 "cpu")

    suspend fun setSelectedModelId(id: String)         { context.dataStore.edit { it[SELECTED_MODEL_ID] = id } }
    suspend fun setSelectedEmbeddingModel(model: String) { context.dataStore.edit { it[SELECTED_EMBEDDING_MODEL] = model } }
    suspend fun setEnableThinking(enabled: Boolean)    { context.dataStore.edit { it[ENABLE_THINKING] = enabled } }
    suspend fun setEnableAgenticRag(enabled: Boolean)  { context.dataStore.edit { it[ENABLE_AGENTIC_RAG] = enabled } }
    suspend fun setTemperature(value: Float)           { context.dataStore.edit { it[TEMPERATURE] = value } }
    suspend fun setTopK(value: Int)                    { context.dataStore.edit { it[TOP_K] = value } }
    suspend fun setMaxOutputTokens(value: Int)         { context.dataStore.edit { it[MAX_OUTPUT_TOKENS] = value } }
    suspend fun setMaxRetrievalChunks(value: Int)      { context.dataStore.edit { it[MAX_RETRIEVAL_CHUNKS] = value } }
    suspend fun setEnableSelfCritique(enabled: Boolean){ context.dataStore.edit { it[ENABLE_SELF_CRITIQUE] = enabled } }
    suspend fun setRetrievalMode(mode: String)         { context.dataStore.edit { it[RETRIEVAL_MODE] = mode } }
    suspend fun setHuggingFaceToken(token: String)     { context.dataStore.edit { it[HUGGINGFACE_TOKEN] = token } }
    suspend fun setActiveSessionId(id: String)         { context.dataStore.edit { it[ACTIVE_SESSION_ID] = id } }
    suspend fun setActiveCollectionId(id: String)      { context.dataStore.edit { it[ACTIVE_COLLECTION_ID] = id } }
    suspend fun setAccelerator(value: String)          { context.dataStore.edit { it[ACCELERATOR] = value } }

    suspend fun getHuggingFaceToken(): String =
        context.dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { it[HUGGINGFACE_TOKEN] ?: "" }
            .first()

    suspend fun getActiveCollectionId(): String =
        context.dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { it[ACTIVE_COLLECTION_ID] ?: DEFAULT_COLLECTION_ID }
            .first()
}
