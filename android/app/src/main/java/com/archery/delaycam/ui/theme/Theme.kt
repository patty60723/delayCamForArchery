package com.archery.delaycam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = AccentOrange,
    background = PhoneBg,
    surface = PanelBg,
    onPrimary = PhoneBg,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = DangerRed,
)

@Composable
fun DelayCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
