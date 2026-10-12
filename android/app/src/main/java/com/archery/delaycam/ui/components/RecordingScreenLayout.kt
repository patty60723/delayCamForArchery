package com.archery.delaycam.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The navigation content keeps one composition location as the recording chrome changes. */
@Composable
fun RecordingScreenLayout(
    practice: Boolean,
    recording: @Composable (compact: Boolean, Modifier) -> Unit,
    content: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding().testTag("recording-screen-layout")) {
        val compact = practice && maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            if (!compact) recording(false, Modifier.fillMaxWidth())
            content(Modifier.weight(1f).fillMaxWidth())
        }
        if (compact) recording(true, Modifier.align(Alignment.TopStart).padding(8.dp)
            .width((maxWidth * 0.46f).coerceAtMost(300.dp)))
    }
}
