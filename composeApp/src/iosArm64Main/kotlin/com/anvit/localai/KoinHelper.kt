package com.anvit.localai

import com.anvit.localai.di.commonModule
import com.anvit.localai.di.iosModule
import org.koin.core.context.startKoin

/**
 * Called once from SwiftUI App.init() to bootstrap Koin on iOS.
 *
 * ```swift
 * @main
 * struct AnvitApp: App {
 *     init() { KoinHelper.shared.doInitKoin() }
 *     ...
 * }
 * ```
 */
object KoinHelper {
    fun doInitKoin() {
        startKoin {
            modules(commonModule, iosModule)
        }
    }
}
