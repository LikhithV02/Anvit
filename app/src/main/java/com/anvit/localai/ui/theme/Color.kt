package com.anvit.localai.ui.theme

import androidx.compose.ui.graphics.Color

// ── Base surfaces (elevation layers) ──────────────────────────────────────────
val Surface0  = Color(0xFF080808)   // true near-black
val Surface1  = Color(0xFF0F0F0F)   // app bar, nav bar
val Surface2  = Color(0xFF171717)   // elevated cards, drawers
val Surface3  = Color(0xFF202020)   // chips, input fields, modal sheets

// Legacy aliases (kept for compatibility)
val Navy900  = Surface0
val Navy800  = Surface1
val Navy700  = Surface2
val Navy600  = Color(0xFF2A2A2A)
val NavyCard = Surface2

// ── Silver / ice-white accent ──────────────────────────────────────────────────
val TealPrimary = Color(0xFFF1F5F9)   // slate-100 — crisp near-white
val TealLight   = Color(0xFFFFFFFF)   // pure white for maximum emphasis
val TealDark    = Color(0xFF94A3B8)   // slate-400 — muted silver for secondary use
val TealSubtle  = Color(0xFFF1F5F91A) // 10% white for subtle fills

// ── Borders ───────────────────────────────────────────────────────────────────
val BorderSubtle  = Color(0xFF1A1A1A)
val BorderDefault = Color(0xFF2A2A2A)
val BorderFocus   = Color(0xFFF1F5F940)  // ~25% white

// ── Text ──────────────────────────────────────────────────────────────────────
val TextPrimary   = Color(0xFFF8FAFC)   // slate-50 — brightest readable white
val TextSecondary = Color(0xFF64748B)   // slate-500 — cool mid-grey
val TextHint      = Color(0xFF334155)   // slate-700 — dim

// ── Chat bubbles ──────────────────────────────────────────────────────────────
val UserBubble       = Color(0xFF272727)   // noticeably raised — query bubble vs. bare response text
val AssistantBubble  = Surface2            // kept for streaming dots fallback
val UserBubbleBorder = Color(0xFF383838)   // hairline border on user bubble

// ── Semantic ──────────────────────────────────────────────────────────────────
val SuccessGreen  = Color(0xFF4ADE80)   // emerald-400
val ErrorRed      = Color(0xFFF87171)   // red-400
val WarningAmber  = Color(0xFFFBBF24)   // amber-400
val AgentStepBlue = Color(0xFF60A5FA)   // blue-400 — only non-grey hue in the UI

// ── Gradient helpers (use with Brush.verticalGradient etc.) ──────────────────
val GradientTop    = Surface0
val GradientBottom = Surface2
