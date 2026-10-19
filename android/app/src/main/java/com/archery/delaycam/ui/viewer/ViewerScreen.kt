package com.archery.delaycam.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.archery.delaycam.ui.DelayCamViewModel
import com.archery.delaycam.ui.components.ViewerShareControls

/** Navigation never owns the camera or the lifetime of the viewing session. */
@Composable
fun ViewerScreen(viewModel: DelayCamViewModel, onBack: () -> Unit, onReturnToPractice: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回上一頁" }) { Text("‹") }
            Text("多裝置", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onReturnToPractice) { Text("返回練習") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp)) { ViewerShareControls(viewModel) }
        }
    }
}
