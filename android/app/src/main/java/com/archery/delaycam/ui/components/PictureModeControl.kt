package com.archery.delaycam.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.archery.delaycam.settings.PictureMode

@Composable
fun PictureModeControl(mode: PictureMode, onChange: (PictureMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("攝影畫面顯示", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PictureMode.entries.forEach { option ->
                OutlinedButton(onClick = { onChange(option) }, modifier = Modifier.weight(1f)
                    .heightIn(min = 48.dp).semantics { selected = mode == option },
                    colors = if (mode == option) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()) {
                    Text(if (option == PictureMode.FIT) "完整畫面" else "滿版")
                }
            }
        }
        Text("完整畫面保留全部動作；滿版填滿視窗，邊緣會裁切。只影響觀看，錄影保留完整畫面。",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
