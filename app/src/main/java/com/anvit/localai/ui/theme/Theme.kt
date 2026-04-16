package com.anvit.localai.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SageDarkColorScheme = darkColorScheme(
    primary              = TealPrimary,          // near-white silver
    onPrimary            = Color(0xFF0A0A0A),     // near-black text on white buttons
    primaryContainer     = Color(0xFF1E1E1E),     // dark container with subtle lift
    onPrimaryContainer   = TealLight,             // white text inside containers
    secondary            = TealDark,              // muted silver
    onSecondary          = TextPrimary,
    secondaryContainer   = Surface2,
    onSecondaryContainer = TextPrimary,
    background           = Surface0,
    onBackground         = TextPrimary,
    surface              = Surface1,
    onSurface            = TextPrimary,
    surfaceVariant       = Surface2,
    onSurfaceVariant     = TextSecondary,
    surfaceContainer     = Surface2,
    surfaceContainerHigh = Surface3,
    outline              = BorderDefault,
    outlineVariant       = BorderSubtle,
    error                = ErrorRed,
    onError              = Color.White
)

@Composable
fun AnvitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SageDarkColorScheme,
        typography  = SageTypography,
        content     = content
    )
}
