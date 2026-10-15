package com.archery.delaycam.ui.gallery

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every stream receives temporary read access, including ACTION_SEND_MULTIPLE. */
fun clipShareIntent(uris: List<Uri>): Intent {
    val items = uris.distinct()
    require(items.isNotEmpty() && items.all { it.scheme == "content" })
    return Intent(if (items.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = "video/mp4"
        if (items.size == 1) putExtra(Intent.EXTRA_STREAM, items.first())
        else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items))
        clipData = ClipData.newRawUri("影片", items.first()).apply {
            items.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

@Stable
class ClipSharingState {
    var busy by mutableStateOf(false); internal set
    var error by mutableStateOf<String?>(null); internal set
    internal var launch: (List<Uri>) -> Unit = {}
    fun share(uris: List<Uri>) { if (!busy && uris.isNotEmpty()) launch(uris.toList()) }
}

@Composable
fun rememberClipSharing(): ClipSharingState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { ClipSharingState() }
    state.launch = { uris ->
        state.busy = true
        state.error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    uris.distinct().forEach { uri ->
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { }
                            ?: throw java.io.IOException("Unreadable clip")
                    }
                }
                val intent = clipShareIntent(uris)
                context.startActivity(Intent.createChooser(intent, "分享影片").apply {
                    clipData = intent.clipData
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { state.error = "無法分享影片，請確認影片仍存在後重試。"
            } finally { state.busy = false }
        }
    }
    return state
}
