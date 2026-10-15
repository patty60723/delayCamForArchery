package com.archery.delaycam.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.archery.delaycam.ui.DelayCamViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ViewerShareControls(viewModel: DelayCamViewModel) {
    val state by viewModel.viewerShare.collectAsState()
    val clipboard = LocalClipboardManager.current
    var selectedLink by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(state.links) { copied = false }
    val link = selectedLink?.takeIf { it in state.links } ?: state.links.firstOrNull()
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text("電腦觀看 · 測試版", style = MaterialTheme.typography.titleSmall)
        Text("手機與電腦連同一個 Wi-Fi，或讓電腦連上這支手機的熱點。電腦用 Chrome／Edge 輸入下方短位址、要求連線，再於手機核對代碼並允許。拍攝 App 需保持前景。",
            Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { if (state.running) viewModel.stopViewerShare() else viewModel.startViewerShare() },
            enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.busy) "正在開啟…" else if (state.running) "關閉觀看連線" else "開啟電腦觀看")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.running) {
            state.requests.forEach { request ->
                Text("電腦要求觀看 · 代碼 ${request.code}", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text("核對電腦上的代碼是否相同。來源：${request.address}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.approveViewerJoin(request.id, false) }) { Text("拒絕") }
                    Button(onClick = { viewModel.approveViewerJoin(request.id, true) }) { Text("允許觀看") }
                }
            }
            Text("只供觀看；本次連結在關閉觀看連線或切到背景後失效。請使用自己的 Wi-Fi 或熱點。片段切換可能短暫停頓。",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (link == null) Text("尚未找到區網位址。連上 Wi-Fi 或開啟熱點後，按「重新取得連結」。")
            state.links.forEach { address ->
                OutlinedButton(onClick = { selectedLink = address; copied = false },
                    modifier = Modifier.semantics { selected = address == link }) {
                    Text(address.substringBefore("/v/"))
                }
            }
            if (link != null) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(link.substringBefore("/v/") + "/", style = MaterialTheme.typography.titleMedium)
                }
                Text("電腦輸入這個短位址即可，不必切到其他 App 傳連結。手機有要求時，核對代碼並允許觀看。", style = MaterialTheme.typography.bodySmall)
                val qr by produceState<Bitmap?>(null, link) {
                    value = withContext(Dispatchers.Default) {
                        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 512, 512)
                        Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                            val pixels = IntArray(512 * 512) { index ->
                                if (matrix[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                            }
                            setPixels(pixels, 0, 512, 0, 0, 512, 512)
                        }
                    }
                }
                Box(Modifier.size(200.dp).padding(vertical = 8.dp)) {
                    qr?.let { Image(it.asImageBitmap(), "掃描本次觀看連結", Modifier.fillMaxSize()) }
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(link, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(link)); copied = true }) { Text("複製觀看連結") }
                if (copied) Text("觀看連結已複製，可貼到電腦瀏覽器", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            TextButton(onClick = viewModel::refreshViewerLinks) { Text("重新取得連結") }
        }
    }
}
