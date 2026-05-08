package com.anvit.localai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import anvit.composeapp.generated.resources.*
import org.jetbrains.compose.resources.Font

// ── Plus Jakarta Sans ─────────────────────────────────────────────────────────

@Composable
fun rememberPlusJakartaSans(): FontFamily {
    val light     = Font(Res.font.plusjakartasans_light,     FontWeight.Light)
    val regular   = Font(Res.font.plusjakartasans_regular,   FontWeight.Normal)
    val italic    = Font(Res.font.plusjakartasans_italic,    FontWeight.Normal, FontStyle.Italic)
    val medium    = Font(Res.font.plusjakartasans_medium,    FontWeight.Medium)
    val semibold  = Font(Res.font.plusjakartasans_semibold,  FontWeight.SemiBold)
    val bold      = Font(Res.font.plusjakartasans_bold,      FontWeight.Bold)
    val extrabold = Font(Res.font.plusjakartasans_extrabold, FontWeight.ExtraBold)
    return remember(light, regular, italic, medium, semibold, bold, extrabold) {
        FontFamily(light, regular, italic, medium, semibold, bold, extrabold)
    }
}

// ── JetBrains Mono ────────────────────────────────────────────────────────────

@Composable
fun rememberJetBrainsMono(): FontFamily {
    val regular  = Font(Res.font.jetbrainsmono_regular,  FontWeight.Normal)
    val medium   = Font(Res.font.jetbrainsmono_medium,   FontWeight.Medium)
    val semibold = Font(Res.font.jetbrainsmono_semibold, FontWeight.SemiBold)
    return remember(regular, medium, semibold) {
        FontFamily(regular, medium, semibold)
    }
}

// ── Typography builder — call from AnvitTheme ─────────────────────────────────

@Composable
fun rememberAnvitTypography(): Typography {
    val pjs = rememberPlusJakartaSans()
    return remember(pjs) {
        Typography(
            headlineMedium = TextStyle(
                fontFamily    = pjs,
                fontWeight    = FontWeight.Bold,
                fontSize      = 22.sp,
                letterSpacing = (-0.3).sp,
            ),
            titleLarge = TextStyle(
                fontFamily    = pjs,
                fontWeight    = FontWeight.SemiBold,
                fontSize      = 17.sp,
                letterSpacing = (-0.2).sp,
            ),
            titleMedium = TextStyle(
                fontFamily = pjs,
                fontWeight = FontWeight.Medium,
                fontSize   = 15.sp,
            ),
            titleSmall = TextStyle(
                fontFamily = pjs,
                fontWeight = FontWeight.Medium,
                fontSize   = 13.sp,
            ),
            bodyLarge = TextStyle(
                fontFamily = pjs,
                fontWeight = FontWeight.Normal,
                fontSize   = 15.sp,
                lineHeight = 22.sp,
            ),
            bodyMedium = TextStyle(
                fontFamily = pjs,
                fontWeight = FontWeight.Normal,
                fontSize   = 14.sp,
                lineHeight = 20.sp,
            ),
            bodySmall = TextStyle(
                fontFamily = pjs,
                fontWeight = FontWeight.Normal,
                fontSize   = 12.sp,
                lineHeight = 17.sp,
            ),
            labelMedium = TextStyle(
                fontFamily    = pjs,
                fontWeight    = FontWeight.Medium,
                fontSize      = 12.sp,
                letterSpacing = 0.2.sp,
            ),
            labelSmall = TextStyle(
                fontFamily    = pjs,
                fontWeight    = FontWeight.Medium,
                fontSize      = 11.sp,
                letterSpacing = 0.4.sp,
            ),
        )
    }
}

// Kept so existing call sites in Theme.kt compile before migration
val AnvitTypography = Typography()
