package com.archery.delaycam.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.archery.delaycam.ui.DelayCamViewModel.ViewerShareState

/** Session availability, not browser presence; never reports an invented online count. */
@Composable
fun ViewerShareStatus(state: ViewerShareState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (!state.running && !state.busy) return
    val label = when {
        state.requests.isNotEmpty() -> "多裝置 · 待確認 ${state.requests.size}"
        state.busy -> "多裝置 · 開啟中"
        else -> "多裝置 · 已開啟"
    }
    FilledTonalButton(onClick = onOpen, modifier = modifier.heightIn(min = 48.dp).semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = if (state.requests.isNotEmpty()) "多裝置，${state.requests.size} 個觀看要求待確認" else label
    }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.primary)) { Text(label, style = MaterialTheme.typography.labelLarge) }
}
