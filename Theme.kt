package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = OrangePrimary,
    onPrimary = TextWhite,
    primaryContainer = OrangePrimary,
    onPrimaryContainer = TextWhite,
    secondary = GrayButton,
    onSecondary = TextWhite,
    background = BgDark,
    onBackground = TextWhite,
    surface = CardDark,
    onSurface = TextWhite,
    surfaceVariant = CardDark,
    onSurfaceVariant = TextMuted,
    outline = BorderStroke
)

@Composable
fun BoLiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
