package com.anvit.localai.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class AnvitPreferences(private val dataStore: DataStore<Preferences>) {

    companion object {
        const val DEFAULT_COLLECTION_ID = "default-collection"
        const val NO_DOCS_SENTINEL = "__no_docs__"

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
        val COMPLIANCE_LAST_SEEN     = longPreferencesKey("compliance_last_seen")
        val USER_EMAIL               = stringPreferencesKey("user_email")
    }

    private fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> =
        dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[key] ?: default }

    val selectedModelId:        Flow<String>  = flow(SELECTED_MODEL_ID,        "gemma4-e2b")
    val selectedEmbeddingModel: Flow<String>  = flow(SELECTED_EMBEDDING_MODEL, "EmbeddingGemma")
    val enableThinking:         Flow<Boolean> = flow(ENABLE_THINKING,           true)
    val enableAgenticRag:       Flow<Boolean> = flow(ENABLE_AGENTIC_RAG,        true)
    val temperature:            Flow<Float>   = flow(TEMPERATURE,               1.0f)
    val topK:                   Flow<Int>     = flow(TOP_K,                     40)
    val maxOutputTokens:        Flow<Int>     = flow(MAX_OUTPUT_TOKENS,         4000)
    val maxRetrievalChunks:     Flow<Int>     = flow(MAX_RETRIEVAL_CHUNKS,      5)
    val enableSelfCritique:     Flow<Boolean> = flow(ENABLE_SELF_CRITIQUE,      true)
    val retrievalMode:          Flow<String>  = flow(RETRIEVAL_MODE,            "hybrid")
    val huggingFaceToken:       Flow<String>  = flow(HUGGINGFACE_TOKEN,         "")
    val activeSessionId:        Flow<String>  = flow(ACTIVE_SESSION_ID,         "")
    val activeCollectionId:     Flow<String>  = flow(ACTIVE_COLLECTION_ID,      DEFAULT_COLLECTION_ID)
    val accelerator:            Flow<String>  = flow(ACCELERATOR,               "cpu")
    val complianceLastSeen:     Flow<Long>    = flow(COMPLIANCE_LAST_SEEN,      0L)
    val userEmail:              Flow<String>  = flow(USER_EMAIL,               "")

    suspend fun setSelectedModelId(id: String)            { dataStore.edit { it[SELECTED_MODEL_ID] = id } }
    suspend fun setSelectedEmbeddingModel(m: String)      { dataStore.edit { it[SELECTED_EMBEDDING_MODEL] = m } }
    suspend fun setEnableThinking(v: Boolean)             { dataStore.edit { it[ENABLE_THINKING] = v } }
    suspend fun setEnableAgenticRag(v: Boolean)           { dataStore.edit { it[ENABLE_AGENTIC_RAG] = v } }
    suspend fun setTemperature(v: Float)                  { dataStore.edit { it[TEMPERATURE] = v } }
    suspend fun setTopK(v: Int)                           { dataStore.edit { it[TOP_K] = v } }
    suspend fun setMaxOutputTokens(v: Int)                { dataStore.edit { it[MAX_OUTPUT_TOKENS] = v } }
    suspend fun setMaxRetrievalChunks(v: Int)             { dataStore.edit { it[MAX_RETRIEVAL_CHUNKS] = v } }
    suspend fun setEnableSelfCritique(v: Boolean)         { dataStore.edit { it[ENABLE_SELF_CRITIQUE] = v } }
    suspend fun setRetrievalMode(m: String)               { dataStore.edit { it[RETRIEVAL_MODE] = m } }
    suspend fun setHuggingFaceToken(t: String)            { dataStore.edit { it[HUGGINGFACE_TOKEN] = t } }
    suspend fun setActiveSessionId(id: String)            { dataStore.edit { it[ACTIVE_SESSION_ID] = id } }
    suspend fun setActiveCollectionId(id: String?)         { dataStore.edit { it[ACTIVE_COLLECTION_ID] = id ?: NO_DOCS_SENTINEL } }
    suspend fun setAccelerator(v: String)                 { dataStore.edit { it[ACCELERATOR] = v } }
    suspend fun setComplianceLastSeen(t: Long)            { dataStore.edit { it[COMPLIANCE_LAST_SEEN] = t } }
    suspend fun setUserEmail(email: String)               { dataStore.edit { it[USER_EMAIL] = email } }

    suspend fun getHuggingFaceToken(): String =
        dataStore.data.catch { emit(emptyPreferences()) }.map { it[HUGGINGFACE_TOKEN] ?: "" }.first()

    suspend fun getActiveCollectionId(): String =
        dataStore.data.catch { emit(emptyPreferences()) }.map { it[ACTIVE_COLLECTION_ID] ?: DEFAULT_COLLECTION_ID }.first()

    suspend fun getChatCollectionId(): String? {
        val raw = dataStore.data.catch { emit(emptyPreferences()) }
            .map { it[ACTIVE_COLLECTION_ID] ?: DEFAULT_COLLECTION_ID }.first()
        return if (raw == NO_DOCS_SENTINEL) null else raw
    }

    suspend fun getComplianceLastSeen(): Long =
        dataStore.data.catch { emit(emptyPreferences()) }.map { it[COMPLIANCE_LAST_SEEN] ?: 0L }.first()
}

/** Platform-specific DataStore provider. */
expect fun createDataStore(): DataStore<Preferences>
