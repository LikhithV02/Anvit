package com.sage.localai.ui.theme

import androidx.compose.ui.graphics.Color

// ── Base surfaces (elevation layers) ──────────────────────────────────────────
val Surface0  = Color(0xFF070D1A)   // deepest background
val Surface1  = Color(0xFF0D1829)   // cards, app bar
val Surface2  = Color(0xFF142030)   // elevated cards, drawers
val Surface3  = Color(0xFF1A2A3E)   // chips, input fields, modal sheets

// Legacy aliases (kept for code that hasn't been migrated yet)
val Navy900  = Surface0
val Navy800  = Surface1
val Navy700  = Surface2
val Navy600  = Color(0xFF1E3659)
val NavyCard = Surface2

// ── Teal accent ───────────────────────────────────────────────────────────────
val TealPrimary = Color(0xFF00C2D4)
val TealLight   = Color(0xFF4DD6E8)
val TealDark    = Color(0xFF0098A8)
val TealSubtle  = Color(0xFF00C2D41A)   // 10% teal for subtle fills

// ── Borders ───────────────────────────────────────────────────────────────────
val BorderSubtle  = Color(0xFF1A2D42)
val BorderDefault = Color(0xFF243D5A)
val BorderFocus   = Color(0xFF00C2D466)  // 40% teal

// ── Text ──────────────────────────────────────────────────────────────────────
val TextPrimary   = Color(0xFFF0F6FF)
val TextSecondary = Color(0xFF8EA5BE)
val TextHint      = Color(0xFF4D6480)

// ── Chat bubbles ──────────────────────────────────────────────────────────────
val UserBubble      = Color(0xFF0D3B5E)   // deep blue user bubble
val AssistantBubble = Surface2            // elevation-2 for assistant
val UserBubbleBorder = Color(0xFF1A6B9A)  // subtle border on user bubble

// ── Semantic ──────────────────────────────────────────────────────────────────
val SuccessGreen  = Color(0xFF3EC97A)
val ErrorRed      = Color(0xFFEF5350)
val WarningAmber  = Color(0xFFFFB74D)
val AgentStepBlue = Color(0xFF40C4FF)

// ── Gradient helpers (use with Brush.verticalGradient etc.) ──────────────────
val GradientTop    = Surface0
val GradientBottom = Surface2
