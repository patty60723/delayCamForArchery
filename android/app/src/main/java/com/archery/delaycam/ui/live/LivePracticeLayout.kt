package com.archery.delaycam.ui.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Practice keeps the entire available video height in every wide viewport. */
@Composable
fun LivePracticeLayout(
    header: @Composable (sidePanel: Boolean) -> Unit,
    video: @Composable (Modifier) -> Unit,
    controls: @Composable (sidePanel: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("practice-layout")) {
        if (maxWidth > maxHeight) {
            val panelWidth = (maxWidth * 0.26f).coerceIn(180.dp, 240.dp)
            Row(Modifier.fillMaxSize()) {
                video(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(panelWidth).fillMaxHeight()
                    .verticalScroll(rememberScrollState()).testTag("practice-controls")) {
                    header(true)
                    controls(true)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header(false)
                video(Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.fillMaxWidth().testTag("practice-controls")) { controls(false) }
            }
        }
    }
}
