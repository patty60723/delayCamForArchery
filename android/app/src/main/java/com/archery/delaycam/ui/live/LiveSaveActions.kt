package com.archery.delaycam.ui.live

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LiveSaveActions(
    enabled: Boolean,
    sidePanel: Boolean,
    onSaveAndReview: () -> Unit,
    onSaveOnly: () -> Unit,
) {
    @Composable fun buttons(first: Modifier, second: Modifier) {
        Button(onClick = onSaveAndReview, enabled = enabled,
            modifier = first.heightIn(min = 56.dp)) { Text("存檔並回看") }
        OutlinedButton(onClick = onSaveOnly, enabled = enabled,
            modifier = second.heightIn(min = 56.dp)) { Text("只保存") }
    }
    val padding = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    if (sidePanel) {
        Column(padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
        }
    } else {
        Row(padding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons(Modifier.weight(1.2f), Modifier.weight(1f))
        }
    }
}
