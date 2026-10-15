package com.archery.delaycam.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.archery.delaycam.settings.CaptureAspect

@Composable
fun CaptureAspectControl(aspect: CaptureAspect, enabled: Boolean, onChange: (CaptureAspect) -> Unit) {
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
        Text(if (enabled) "4:3 保留較多構圖；16:9 會裁切邊緣。直拿時分別為 3:4、9:16。影響新拍攝與存檔，延遲畫面稍後更新。"
            else "錄影中，停止錄影後可切換取景比例。",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
