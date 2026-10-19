package com.archery.delaycam.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.archery.delaycam.ui.DelayCamViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The sole owner of the phone-side viewing and approval controls. */
@Composable
fun ViewerShareControls(viewModel: DelayCamViewModel) {
    val state by viewModel.viewerShare.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val app = context.applicationContext as com.archery.delaycam.DelayCamApp
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.startViewerShare() }
    val startSharing = {
        if (Build.VERSION.SDK_INT >= 33 && !app.notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            app.notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else viewModel.startViewerShare()
    }
    var selectedLink by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.links) { copied = false }
    val link = selectedLink?.takeIf { it in state.links } ?: state.links.firstOrNull()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("電腦觀看 · 測試版", style = MaterialTheme.typography.titleMedium)
        Text(when {
            state.busy -> "正在開啟觀看連線…"
            state.running -> "觀看連線已開啟"
            else -> "尚未開啟觀看連線"
        }, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text("手機與電腦連同一個 Wi-Fi，或讓電腦連上這支手機的熱點。電腦用 Chrome／Edge 觀看。開啟後可切到 LINE 或其他 App，拍攝與連結會繼續。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Text("先開好 Wi-Fi／熱點，再開啟觀看。背景會繼續拍攝與整段錄影，通知會顯示分享狀態；背景停止分享時，錄影會結束並保存。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.running && !com.archery.delaycam.service.ViewerForegroundService.notificationsVisible(context)) {
            Text("分享通知未開啟，分享仍會繼續；請回到此頁停止，或使用 Android 的執行中應用程式控制。", style = MaterialTheme.typography.bodySmall)
        }
        if (state.running) {
            state.requests.forEach { request ->
                Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("電腦要求觀看", style = MaterialTheme.typography.titleSmall)
                        Text("核對碼 ${request.code}", style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text("確認與電腦顯示的代碼相同再允許。來源：${request.address}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.approveViewerJoin(request.id, false) },
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("拒絕") }
                            Button(onClick = { viewModel.approveViewerJoin(request.id, true) },
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("允許觀看") }
                        }
                    }
                }
            }
            if (link == null) {
                Text("尚未找到可用的 Wi-Fi／熱點位址；行動網路位址不能供對方連入。先開啟熱點或連上 Wi-Fi，再按「重新取得連結」。")
            } else {
                Text("在電腦瀏覽器輸入", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(link.substringBefore("/v/") + "/", style = MaterialTheme.typography.headlineSmall)
                }
                Text("請完整輸入 http:// 開頭的網址，再按「向手機要求觀看」。核對兩邊代碼後允許；返回練習後仍可從待確認提示進入。",
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { stateDescription = if (advanced) "已展開" else "已收起" }) {
                    Text(if (advanced) "收起 QR Code 與其他連線方式" else "QR Code 與其他連線方式")
                }
                if (advanced) {
                    if (state.links.size > 1) {
                        Text("其他網路位址", style = MaterialTheme.typography.titleSmall)
                        Text("若電腦無法開啟，選擇與電腦同一網路的位址。", style = MaterialTheme.typography.bodySmall)
                        state.links.forEach { address ->
                            OutlinedButton(onClick = { selectedLink = address; copied = false },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { selected = address == link }) {
                                Text(address.substringBefore("/v/") + "/")
                            }
                        }
                    }
                    Text("下方 QR Code 與完整連結可直接觀看，只提供給自己的裝置。可以切到通訊 App 傳送；主動停止分享後，本次連結才會失效。",
                        style = MaterialTheme.typography.bodySmall)
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
                    Box(Modifier.size(200.dp)) {
                        qr?.let { Image(it.asImageBitmap(), "掃描本次觀看連結", Modifier.fillMaxSize()) }
                    }
                    SelectionContainer { Text(link, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(link)); copied = true },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("複製觀看連結") }
                    if (copied) Text("觀看連結已複製，可貼到電腦瀏覽器",
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            TextButton(onClick = viewModel::refreshViewerLinks, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新取得連結") }
            Text("返回練習、切換 App 或回桌面都會繼續分享。可在這裡或通知停止；滑掉 App、系統結束服務或主動停止後，本次連結會失效。",
                style = MaterialTheme.typography.bodySmall)
            Text("只供觀看，尚未記住裝置。請使用自己的 Wi-Fi 或熱點；片段切換可能短暫停頓。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = viewModel::stopViewerShare, modifier = Modifier.heightIn(min = 48.dp)) { Text("關閉觀看連線") }
        } else {
            Button(onClick = startSharing, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(if (state.busy) "正在開啟…" else "開啟電腦觀看")
            }
        }
    }
}
