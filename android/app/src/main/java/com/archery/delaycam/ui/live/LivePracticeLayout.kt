package com.archery.delaycam.ui.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Landscape controls overlay the video; opening them never changes the player bounds. */
@Composable
fun LivePracticeLayout(
    header: @Composable (sidePanel: Boolean) -> Unit,
    video: @Composable (Modifier) -> Unit,
    controls: @Composable (sidePanel: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    landscapeFeedback: @Composable (Modifier) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize().testTag("practice-layout")) {
        if (maxWidth > maxHeight) {
            val panelWidth = (maxWidth * 0.42f).coerceIn(220.dp, 300.dp)
            BackHandler(enabled = expanded) { expanded = false }
            video(Modifier.fillMaxSize())
            val feedbackWidth = (if (expanded) maxWidth - panelWidth - 24.dp else maxWidth - 16.dp)
                .coerceAtLeast(120.dp).coerceAtMost(400.dp)
            landscapeFeedback(Modifier.align(Alignment.BottomStart).padding(8.dp).width(feedbackWidth))
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .width(if (expanded) panelWidth else 88.dp)
                    .then(if (expanded) Modifier.fillMaxHeight() else Modifier),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column {
                    TextButton(onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("practice-control-toggle").semantics {
                                contentDescription = if (expanded) "收起操作" else "顯示操作"
                                stateDescription = if (expanded) "已展開" else "已收起"
                            }) { Text(if (expanded) "收起" else "操作") }
                    if (expanded) Column(Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).testTag("practice-controls")) {
                        header(true)
                        controls(true)
                    }
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
