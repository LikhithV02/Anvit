package com.sage.localai

import android.app.Application
import android.util.Log
import com.sage.localai.data.db.SageDatabase
import com.sage.localai.data.preferences.SagePreferences
import com.sage.localai.download.ModelDownloadService
import com.sage.localai.embedding.GeckoEmbeddingService
import com.sage.localai.inference.GemmaInferenceService
import com.sage.localai.inference.RagAgentTools
import com.sage.localai.retrieval.HybridRetriever

class SageApplication : Application() {

    val database       by lazy { SageDatabase.getInstance(this) }
    val preferences    by lazy { SagePreferences(this) }
    val embeddingService by lazy { GeckoEmbeddingService(this) }
    val inferenceService by lazy { GemmaInferenceService(this) }
    val hybridRetriever  by lazy {
        HybridRetriever(database.documentDao(), embeddingService)
    }
    val modelDownloadService by lazy { ModelDownloadService(this) }
    val ragAgentTools by lazy {
        RagAgentTools(hybridRetriever).also {
            inferenceService.setRagTools(it)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("SageApp", "Sage application starting")
        ragAgentTools // pre-initialize
    }
}
