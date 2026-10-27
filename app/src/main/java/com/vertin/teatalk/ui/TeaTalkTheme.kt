package com.vertin.teatalk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TeaTalkColors = lightColorScheme(
    primary = Color(0xFF00696D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F2F0),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFFC2410C),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF14201F),
    surface = Color(0xFFF4F8F7),
    onSurface = Color(0xFF14201F),
    surfaceVariant = Color(0xFFEAF3F1),
    onSurfaceVariant = Color(0xFF536562),
    outline = Color(0xFFDCE7E5),
)

@Composable
fun TeaTalkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TeaTalkColors, content = content)
}
