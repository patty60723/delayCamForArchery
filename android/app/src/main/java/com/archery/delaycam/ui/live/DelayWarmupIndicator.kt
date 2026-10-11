package com.archery.delaycam.ui.live

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Zero still waits for a rendered frame; the spinner always explains its purpose. */
@Composable
fun DelayWarmupIndicator(remainingSec: Int, delaySec: Int, modifier: Modifier = Modifier) {
    val seconds = remainingSec.coerceAtLeast(0)
    Surface(modifier.widthIn(max = 320.dp).testTag("delay-warmup"),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("準備延遲畫面", style = MaterialTheme.typography.titleSmall)
            Box(Modifier.size(72.dp).semantics { contentDescription = "暖機剩餘 $seconds 秒" },
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 3.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(seconds.toString(), style = MaterialTheme.typography.headlineSmall)
                    Text("秒", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(if (seconds > 0) "正在建立 $delaySec 秒延遲，請稍候"
                else "正在等待第一個延遲畫面…",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}
