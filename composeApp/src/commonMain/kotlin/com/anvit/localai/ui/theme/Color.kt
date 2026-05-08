package com.anvit.localai.ui.theme

import androidx.compose.ui.graphics.Color

// ── AnvitColors — all semantic tokens for one theme palette ──────────────────

data class AnvitColors(
    val bg: Color,
    val surf0: Color,
    val surf1: Color,
    val surf2: Color,
    val surf3: Color,
    val surf4: Color,
    val accent: Color,
    val accentB: Color,
    val accentDim: Color,
    val accentGlow: Color,
    val pink: Color,
    val pinkDim: Color,
    val amber: Color,
    val amberDim: Color,
    val green: Color,
    val greenDim: Color,
    val txt0: Color,
    val txt1: Color,
    val txt2: Color,
    val txt3: Color,
    val border: Color,
    val border2: Color,
    val isDark: Boolean,
)

// ── Midnight (dark) ───────────────────────────────────────────────────────────

val AnvitMidnightColors = AnvitColors(
    bg       = Color(0xFF060A0F),
    surf0    = Color(0xFF0A1018),
    surf1    = Color(0xFF101820),
    surf2    = Color(0xFF182230),
    surf3    = Color(0xFF1E2C3C),
    surf4    = Color(0xFF25374A),
    accent   = Color(0xFF00C8E8),
    accentB  = Color(0xFF0099B8),
    accentDim  = Color(0xFF00C8E8).copy(alpha = 0.10f),
    accentGlow = Color(0xFF00C8E8).copy(alpha = 0.28f),
    pink     = Color(0xFFF472B6),
    pinkDim  = Color(0xFFF472B6).copy(alpha = 0.12f),
    amber    = Color(0xFFFBBF24),
    amberDim = Color(0xFFFBBF24).copy(alpha = 0.12f),
    green    = Color(0xFF34D399),
    greenDim = Color(0xFF34D399).copy(alpha = 0.12f),
    txt0     = Color(0xFFE8F4FF),
    txt1     = Color(0xFF8BAFC8),
    txt2     = Color(0xFF4D6B80),
    txt3     = Color(0xFF2A3D4D),
    border   = Color(0xFF00C8E8).copy(alpha = 0.10f),
    border2  = Color(0xFF00C8E8).copy(alpha = 0.22f),
    isDark   = true,
)

// ── Dawn (light) ──────────────────────────────────────────────────────────────

val AnvitDawnColors = AnvitColors(
    bg       = Color(0xFFF0F6FA),
    surf0    = Color(0xFFFFFFFF),
    surf1    = Color(0xFFF7FBFD),
    surf2    = Color(0xFFEAF4F9),
    surf3    = Color(0xFFD8EDF6),
    surf4    = Color(0xFFC4E2F0),
    accent   = Color(0xFF0099BA),
    accentB  = Color(0xFF00778F),
    accentDim  = Color(0xFF0099BA).copy(alpha = 0.10f),
    accentGlow = Color(0xFF0099BA).copy(alpha = 0.22f),
    pink     = Color(0xFFDB2777),
    pinkDim  = Color(0xFFDB2777).copy(alpha = 0.08f),
    amber    = Color(0xFFD97706),
    amberDim = Color(0xFFD97706).copy(alpha = 0.10f),
    green    = Color(0xFF047857),
    greenDim = Color(0xFF047857).copy(alpha = 0.10f),
    txt0     = Color(0xFF0A2030),
    txt1     = Color(0xFF2A5068),
    txt2     = Color(0xFF6090A8),
    txt3     = Color(0xFFAAC8D8),
    border   = Color(0xFF0099BA).copy(alpha = 0.14f),
    border2  = Color(0xFF0099BA).copy(alpha = 0.28f),
    isDark   = false,
)

// ── Semantic (theme-independent) ──────────────────────────────────────────────

val SuccessGreen  = Color(0xFF4ADE80)
val ErrorRed      = Color(0xFFF87171)
val WarningAmber  = Color(0xFFFBBF24)

// Agent step palette (same in both themes)
val StepPlanning     = Color(0xFF9B87F5)
val StepBreaking     = Color(0xFFFBBF24)
val StepSearching    = Color(0xFF34D399)
val StepSupplementing = Color(0xFF60A5FA)
val StepFiltering    = Color(0xFFF472B6)
val StepGenerating   = Color(0xFFA78BFA)
