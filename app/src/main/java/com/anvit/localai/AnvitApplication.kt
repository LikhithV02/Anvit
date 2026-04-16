package com.anvit.localai

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.anvit.localai.data.db.AnvitDatabase
import com.anvit.localai.data.preferences.AnvitPreferences
import com.anvit.localai.download.ModelDownloadService
import com.anvit.localai.embedding.GeckoEmbeddingService
import com.anvit.localai.inference.GemmaInferenceService
import com.anvit.localai.inference.RagAgentTools
import com.anvit.localai.retrieval.HybridRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AnvitApplication : Application() {

    // Application-level scope that outlives any single ViewModel
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database       by lazy { AnvitDatabase.getInstance(this) }
    val preferences    by lazy { AnvitPreferences(this) }
    val embeddingService by lazy { GeckoEmbeddingService(this) }
    val inferenceService by lazy { GemmaInferenceService(this) }
    val hybridRetriever  by lazy {
        HybridRetriever(database.documentDao(), embeddingService)
    }
    val modelDownloadService by lazy { ModelDownloadService(this) }
    val ragAgentTools by lazy {
        // Pass `this` so the tools can fire Android intents (email, SMS, map)
        RagAgentTools(hybridRetriever, this).also {
            inferenceService.setRagTools(it)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("AnvitApp", "Anvit application starting")
        ragAgentTools // pre-initialize
    }

    /**
     * Android calls this when the system is running low on memory.
     * Releasing the engine at critical levels prevents OOM crashes and
     * reduces the chance of the LMK killing our process entirely.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // System is about to kill us — unload the engine immediately
                Log.w("AnvitApp", "onTrimMemory COMPLETE — unloading engine to avoid OOM")
                appScope.launch { inferenceService.unloadModel() }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // Running critically low — unload engine to free RAM
                Log.w("AnvitApp", "onTrimMemory RUNNING_CRITICAL — unloading engine")
                appScope.launch { inferenceService.unloadModel() }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // Low but not critical — log for now; engine stays loaded
                Log.w("AnvitApp", "onTrimMemory RUNNING_LOW — memory pressure detected")
            }
        }
    }
}
