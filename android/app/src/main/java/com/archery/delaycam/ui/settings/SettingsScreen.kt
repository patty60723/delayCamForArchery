package com.archery.delaycam.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.archery.delaycam.settings.AppSettings
import com.archery.delaycam.settings.DominantHand
import com.archery.delaycam.ui.DelayCamViewModel

/** Settings screen (spec 3.3 / 4). */
@Composable
fun SettingsScreen(viewModel: DelayCamViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Text("‹", color = Color.White) }
            Text("設定", color = Color.White)
        }

        SliderField(
            label = "延遲秒數",
            valueLabel = "${settings.delaySec} 秒",
            value = settings.delaySec.toFloat(),
            range = AppSettings.DELAY_RANGE.first.toFloat()..AppSettings.DELAY_RANGE.last.toFloat(),
            onValueChange = { v -> viewModel.updateSettings { it.copy(delaySec = v.toInt()) } },
            description = "主畫面持續延遲顯示的秒數",
        )

        SliderField(
            label = "回溯存檔範圍",
            valueLabel = "${settings.backtrackSec} 秒",
            value = settings.backtrackSec.toFloat(),
            range = AppSettings.BACKTRACK_RANGE.first.toFloat()..AppSettings.BACKTRACK_RANGE.last.toFloat(),
            onValueChange = { v -> viewModel.updateSettings { it.copy(backtrackSec = v.toInt()) } },
            description = "比手勢時,往回存多少秒的內容",
        )

        SwitchField(
            label = "垂直參考線",
            checked = settings.guideVertical,
            onCheckedChange = { v -> viewModel.updateSettings { it.copy(guideVertical = v) } },
        )
        SwitchField(
            label = "水平參考線",
            checked = settings.guideHorizontal,
            onCheckedChange = { v -> viewModel.updateSettings { it.copy(guideHorizontal = v) } },
        )

        Text("慢動作回放速度", color = Color.White, modifier = Modifier.padding(top = 16.dp))
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.SLOW_MO_OPTIONS.forEach { rate ->
                val label = when (rate) {
                    1f -> "1x"
                    0.5f -> "1/2x"
                    else -> "1/4x"
                }
                val selected = settings.slowMoRate == rate
                Button(
                    onClick = { viewModel.updateSettings { it.copy(slowMoRate = rate) } },
                    colors = if (selected) {
                        androidx.compose.material3.ButtonDefaults.buttonColors()
                    } else {
                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                    },
                ) { Text(label) }
            }
        }

        Text("慣用手", color = Color.White, modifier = Modifier.padding(top = 16.dp))
        Text(
            "手勢辨識已改為偵測全畫面;這個設定只決定即時小視窗顯示在畫面左上或右上角,避免被比手勢的那隻手擋住",
            color = Color(0xFF9AA1AB),
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DominantHand.entries.forEach { hand ->
                val label = if (hand == DominantHand.RIGHT) "右" else "左"
                val selected = settings.dominantHand == hand
                Button(
                    onClick = { viewModel.updateSettings { it.copy(dominantHand = hand) } },
                    colors = if (selected) {
                        androidx.compose.material3.ButtonDefaults.buttonColors()
                    } else {
                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                    },
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun SliderField(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    description: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White)
            Text(valueLabel, color = Color(0xFFFF7A1A))
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
        Text(description, color = Color(0xFF9AA1AB))
    }
}

@Composable
private fun SwitchField(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
