package com.archery.delaycam.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.archery.delaycam.settings.CaptureAspect

@Composable
fun CaptureAspectControl(aspect: CaptureAspect, enabled: Boolean, sourceStatus: com.archery.delaycam.camera.CameraSourceStatus? = null, onChange: (CaptureAspect) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("取景比例", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureAspect.entries.forEach { option ->
                OutlinedButton(onClick = { onChange(option) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = aspect == option },
                    colors = if (aspect == option) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()) {
                    Text(if (option == CaptureAspect.FOUR_THREE) "4:3" else "16:9")
                }
            }
        }
        Text(if (enabled) "相機會優先使用所選比例，不支援 16:9 時改用 4:3 來源再取景，仍可能裁切。直拿時為 3:4、9:16。切換可能短暫停頓，延遲畫面稍後更新。"
            else "錄影中，停止錄影後可切換取景比例。",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        sourceStatus?.let { state ->
            val message = when {
                state.failed -> "相機無法啟動，請關閉後重新開啟 App。"
                state.switching -> "正在切換相機比例…"
                state.source == CaptureAspect.FOUR_THREE && state.requested == CaptureAspect.SIXTEEN_NINE ->
                    "相機來源：4:3（16:9 無法使用，已改用 4:3 來源）"
                state.source == CaptureAspect.SIXTEEN_NINE -> "相機來源：16:9"
                else -> "相機來源：4:3"
            }
            Text(message, Modifier.padding(top = 8.dp).heightIn(min = 40.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
