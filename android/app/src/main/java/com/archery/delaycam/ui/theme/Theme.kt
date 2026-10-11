package com.archery.delaycam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

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
    MaterialTheme(colorScheme = DarkColors) {
        // MaterialTheme owns the palette; plain Text also needs a default ink
        // color when the app content is placed directly on a dark background.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            content = content)
    }
}

