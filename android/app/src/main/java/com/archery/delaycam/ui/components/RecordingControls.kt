package com.archery.delaycam.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.archery.delaycam.camera.RecordingPhase
import com.archery.delaycam.state.SavePhase
import com.archery.delaycam.state.SaveStatus

@Composable
fun RecordingControls(
    phase: RecordingPhase,
    elapsedSec: Int,
    save: SaveStatus,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onOpenSaved: (Uri, String) -> Unit,
) {
    val busy = phase == RecordingPhase.STARTING || phase == RecordingPhase.STOPPING ||
        save.phase == SavePhase.SAVING
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                val title = when (phase) {
                    RecordingPhase.STARTING -> "正在開始錄影…"
                    RecordingPhase.RECORDING -> "● 錄影中 ${formatMediaTime(elapsedSec * 1000L)}"
                    RecordingPhase.STOPPING -> "正在完成錄影…"
                    RecordingPhase.IDLE -> "整段錄影"
                }
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = if (phase == RecordingPhase.RECORDING) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface)
                Text(
                    text = if (save.phase != SavePhase.IDLE) save.message
                    else if (phase == RecordingPhase.RECORDING) "回放時相機持續錄影"
                    else "錄下練習，方便事後研究",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (save.phase == SavePhase.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (save.phase == SavePhase.SAVED && save.uri != null) {
                TextButton(onClick = { onOpenSaved(save.uri, save.displayName) }) { Text("查看") }
            }
            if (save.phase == SavePhase.FAILED && save.retryable) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重試保存") }
            } else {
                OutlinedButton(onClick = onToggle, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(when {
                        phase == RecordingPhase.RECORDING -> "停止錄影"
                        busy -> "處理中…"
                        save.phase == SavePhase.SAVED -> "繼續錄影"
                        else -> "開始錄影"
                    })
                }
            }
        }
    }
}

@Composable
fun SaveStatusNotice(
    save: SaveStatus,
    onRetry: () -> Unit,
    onOpenSaved: (Uri, String) -> Unit,
) {
    if (save.phase == SavePhase.IDLE) return
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(save.message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = if (save.phase == SavePhase.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface)
        if (save.phase == SavePhase.SAVING) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
        }
        if (save.phase == SavePhase.FAILED && save.retryable) {
            TextButton(onClick = onRetry) { Text("重試保存") }
        }
        if (save.phase == SavePhase.SAVED && save.uri != null) {
            TextButton(onClick = { onOpenSaved(save.uri, save.displayName) }) { Text("查看影片") }
        }
    }
}

fun formatMediaTime(milliseconds: Long): String {
    val totalSec = milliseconds.coerceAtLeast(0) / 1000
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}
