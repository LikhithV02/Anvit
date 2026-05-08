package com.anvit.localai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

// ── Theme mode preference ─────────────────────────────────────────────────────

enum class ThemeMode { SYSTEM, LIGHT, DARK }

fun String.toThemeMode(): ThemeMode = when (this) {
    "light"  -> ThemeMode.LIGHT
    "dark"   -> ThemeMode.DARK
    else     -> ThemeMode.SYSTEM
}

// ── CompositionLocal for AnvitColors ─────────────────────────────────────────

val LocalAnvitColors = staticCompositionLocalOf { AnvitMidnightColors }

// ── Material3 color scheme helpers ───────────────────────────────────────────

private fun anvilDarkColorScheme(c: AnvitColors) = darkColorScheme(
    primary              = c.accent,
    onPrimary            = Color(0xFF001820),
    primaryContainer     = c.surf2,
    onPrimaryContainer   = c.txt0,
    secondary            = c.txt1,
    onSecondary          = c.txt0,
    secondaryContainer   = c.surf3,
    onSecondaryContainer = c.txt0,
    background           = c.bg,
    onBackground         = c.txt0,
    surface              = c.surf1,
    onSurface            = c.txt0,
    surfaceVariant       = c.surf2,
    onSurfaceVariant     = c.txt1,
    surfaceContainer     = c.surf2,
    surfaceContainerHigh = c.surf3,
    outline              = c.border2,
    outlineVariant       = c.border,
    error                = ErrorRed,
    onError              = Color.White,
)

private fun anvilLightColorScheme(c: AnvitColors) = lightColorScheme(
    primary              = c.accent,
    onPrimary            = Color.White,
    primaryContainer     = c.surf3,
    onPrimaryContainer   = c.txt0,
    secondary            = c.txt1,
    onSecondary          = Color.White,
    secondaryContainer   = c.surf2,
    onSecondaryContainer = c.txt0,
    background           = c.bg,
    onBackground         = c.txt0,
    surface              = c.surf0,
    onSurface            = c.txt0,
    surfaceVariant       = c.surf2,
    onSurfaceVariant     = c.txt1,
    surfaceContainer     = c.surf1,
    surfaceContainerHigh = c.surf2,
    outline              = c.border2,
    outlineVariant       = c.border,
    error                = ErrorRed,
    onError              = Color.White,
)

// ── AnvitTheme ────────────────────────────────────────────────────────────────

@Composable
fun AnvitTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isDark = when (mode) {
        ThemeMode.DARK   -> true
        ThemeMode.LIGHT  -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (isDark) AnvitMidnightColors else AnvitDawnColors
    val typography = rememberAnvitTypography()
    CompositionLocalProvider(LocalAnvitColors provides colors) {
        MaterialTheme(
            colorScheme = if (isDark) anvilDarkColorScheme(colors) else anvilLightColorScheme(colors),
            typography  = typography,
            content     = content,
        )
    }
}
