package com.anvit.localai

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import com.anvit.localai.data.preferences.AnvitPreferences
import com.anvit.localai.ui.navigation.AnvitNavHost
import com.anvit.localai.ui.theme.AnvitTheme
import com.anvit.localai.ui.theme.toThemeMode
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private object IosThemeProvider : KoinComponent {
    val prefs: AnvitPreferences by inject()
}

fun MainViewController() = ComposeUIViewController {
    val themeModeStr by IosThemeProvider.prefs.themeMode.collectAsState(initial = "system")
    AnvitTheme(mode = themeModeStr.toThemeMode()) {
        AnvitNavHost()
    }
}
