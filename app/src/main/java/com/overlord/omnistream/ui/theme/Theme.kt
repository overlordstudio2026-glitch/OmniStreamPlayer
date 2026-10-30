package com.overlord.omnistream.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    secondary = AmberAccent,
    background = BgDark,
    surface = SurfaceDark,
    onPrimary = BgDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun OmniStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
