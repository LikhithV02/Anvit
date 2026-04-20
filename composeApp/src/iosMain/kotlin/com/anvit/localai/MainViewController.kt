package com.anvit.localai

import androidx.compose.ui.window.ComposeUIViewController
import com.anvit.localai.ui.navigation.AnvitNavHost
import com.anvit.localai.ui.theme.AnvitTheme

/**
 * Called from Swift ContentView to embed the entire Compose UI.
 *
 * Swift: MainViewControllerKt.MainViewController()
 */
fun MainViewController() = ComposeUIViewController {
    AnvitTheme {
        AnvitNavHost()
    }
}
