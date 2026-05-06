package com.anvit.localai

import com.anvit.localai.di.commonModule
import com.anvit.localai.di.iosModule
import org.koin.core.context.startKoin

/**
 * Called once from SwiftUI App.init() to bootstrap Koin on iOS Simulator.
 */
object KoinHelper {
    fun doInitKoin() {
        startKoin {
            allowOverride(true)
            modules(commonModule, iosModule)
        }
    }
}
