package com.archery.delaycam.ui.components

import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.archery.delaycam.ui.DelayCamViewModel

/** One confirmation and busy/failure contract for saved clips and the current shot. */
@Composable
fun DeleteClipAction(viewModel: DelayCamViewModel, uri: Uri?, name: String,
    label: String = "刪除影片", onDeleted: () -> Unit, onInteraction: () -> Unit = {}, modifier: Modifier = Modifier) {
    val deletion by viewModel.clipDeletion.collectAsState()
    var confirm by remember(uri) { mutableStateOf(false) }
    val isThisClip = deletion.uri == uri
    Column(modifier) {
        TextButton(onClick = { onInteraction(); confirm = true }, enabled = uri != null && !deletion.busy,
            modifier = Modifier.fillMaxWidth()) { Text(if (isThisClip && deletion.busy) "刪除中…" else label,
                color = MaterialTheme.colorScheme.error) }
        if (isThisClip && deletion.error != null) Text(deletion.error!!, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    if (confirm && uri != null) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(if (label == "刪除單箭") "刪除這一箭？" else "刪除這段影片？") },
        text = { Text("$name\n會刪除手機中已保存的影片，無法從 App 復原。") },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("保留影片") } },
        confirmButton = { TextButton(onClick = {
            confirm = false
            viewModel.deleteClip(uri, onDeleted)
        }) { Text("確認刪除", color = MaterialTheme.colorScheme.error) } })
}
