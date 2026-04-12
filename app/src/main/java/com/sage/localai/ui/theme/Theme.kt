package com.sage.localai.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SageDarkColorScheme = darkColorScheme(
    primary            = TealPrimary,
    onPrimary          = Surface0,
    primaryContainer   = Surface3,
    onPrimaryContainer = TealLight,
    secondary          = TealDark,
    onSecondary        = TextPrimary,
    secondaryContainer = Surface2,
    onSecondaryContainer = TextPrimary,
    background         = Surface0,
    onBackground       = TextPrimary,
    surface            = Surface1,
    onSurface          = TextPrimary,
    surfaceVariant     = Surface2,
    onSurfaceVariant   = TextSecondary,
    surfaceContainer   = Surface2,
    surfaceContainerHigh = Surface3,
    outline            = BorderDefault,
    outlineVariant     = BorderSubtle,
    error              = ErrorRed,
    onError            = Color.White
)

@Composable
fun SageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SageDarkColorScheme,
        typography  = SageTypography,
        content     = content
    )
}
