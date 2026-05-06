package com.anvit.localai

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.anvit.localai.di.androidModule
import com.anvit.localai.di.commonModule
import com.anvit.localai.inference.InferenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class AnvitApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // 1. Seed the context holder used by commonModule's expect actuals
        AnvitContextHolder.appContext = this

        // 2. Bootstrap Koin
        startKoin {
            allowOverride(true)
            androidLogger(Level.ERROR)
            androidContext(this@AnvitApplication)
            modules(commonModule, androidModule)
        }

        Log.d("AnvitApp", "Koin started — Anvit application ready")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w("AnvitApp", "onTrimMemory COMPLETE — unloading engine to avoid OOM")
                try {
                    val svc: InferenceService = get()
                    appScope.launch { svc.unloadModel() }
                } catch (e: Exception) {
                    Log.w("AnvitApp", "Could not unload engine (Koin not ready?): ${e.message}")
                }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.w("AnvitApp", "onTrimMemory RUNNING_CRITICAL — unloading engine")
                try {
                    val svc: InferenceService = get()
                    appScope.launch { svc.unloadModel() }
                } catch (e: Exception) {
                    Log.w("AnvitApp", "Could not unload engine (Koin not ready?): ${e.message}")
                }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.w("AnvitApp", "onTrimMemory RUNNING_LOW — memory pressure detected")
            }
        }
    }
}
