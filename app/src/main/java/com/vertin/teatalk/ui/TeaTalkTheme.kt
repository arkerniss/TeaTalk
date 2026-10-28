package com.vertin.teatalk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * "Paper & Clay" — editorial + terminal hybrid.
 *
 * One accent (clay), warm paper, hairline rules, serif display, monospace metadata.
 * Every screen reads its colors from [LocalTeaPalette]; nothing hardcodes a color
 * except the always-white QR panel.
 */
@Immutable
data class TeaPalette(
    val paper: Color,
    val raised: Color,
    val sunken: Color,
    val deep: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkMuted: Color,
    val rule: Color,
    val clay: Color,
    val clayInk: Color,
    val danger: Color,
    val qrSurface: Color,
    val dark: Boolean,
)

val LightTeaPalette: TeaPalette = TeaPalette(
    paper = Color(0xFFFAF8F3),
    raised = Color(0xFFFFFFFF),
    sunken = Color(0xFFF1EDE4),
    deep = Color(0xFFE7E1D4),
    ink = Color(0xFF1B1A17),
    inkSoft = Color(0xFF4E4A43),
    inkMuted = Color(0xFF6B665B),
    rule = Color(0xFFE3DDD0),
    clay = Color(0xFFAE5129),
    clayInk = Color(0xFFFDF9F4),
    danger = Color(0xFFA33A2B),
    qrSurface = Color(0xFFFFFFFF),
    dark = false,
)

val DarkTeaPalette: TeaPalette = TeaPalette(
    paper = Color(0xFF121110),
    raised = Color(0xFF1B1A18),
    sunken = Color(0xFF24221F),
    deep = Color(0xFF2E2B26),
    ink = Color(0xFFF2EEE5),
    inkSoft = Color(0xFFC4BEB2),
    inkMuted = Color(0xFF938C7E),
    rule = Color(0xFF2E2B27),
    clay = Color(0xFFD9784F),
    clayInk = Color(0xFF1A1008),
    danger = Color(0xFFE28A78),
    qrSurface = Color(0xFFFFFFFF),
    dark = true,
)

val LocalTeaPalette = staticCompositionLocalOf { LightTeaPalette }

/** Radii used across the design system. */
object TeaShapes {
    val sm = RoundedCornerShape(12.dp)
    val md = RoundedCornerShape(16.dp)
    val lg = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(999.dp)
}

/** Type scale. System families only — no downloaded fonts. */
object TeaType {
    val display = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        letterSpacing = (-0.02).em,
    )

    val title = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        letterSpacing = (-0.01).em,
    )

    val heading = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
    )

    val body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )

    val bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )

    val meta = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.sp,
    )

    val label = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.08.em,
    )
}

private fun teaColorScheme(palette: TeaPalette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = palette.clay,
        onPrimary = palette.clayInk,
        background = palette.paper,
        onBackground = palette.ink,
        surface = palette.raised,
        onSurface = palette.ink,
        surfaceVariant = palette.sunken,
        onSurfaceVariant = palette.inkMuted,
        outline = palette.rule,
        error = palette.danger,
        onError = palette.clayInk,
    )
} else {
    lightColorScheme(
        primary = palette.clay,
        onPrimary = palette.clayInk,
        background = palette.paper,
        onBackground = palette.ink,
        surface = palette.raised,
        onSurface = palette.ink,
        surfaceVariant = palette.sunken,
        onSurfaceVariant = palette.inkMuted,
        outline = palette.rule,
        error = palette.danger,
        onError = palette.clayInk,
    )
}

@Composable
fun TeaTalkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkTeaPalette else LightTeaPalette
    CompositionLocalProvider(LocalTeaPalette provides palette) {
        MaterialTheme(
            colorScheme = teaColorScheme(palette, darkTheme),
            content = content,
        )
    }
}
