package com.archery.delaycam.viewer

import com.archery.delaycam.camera.ImmutableSegmentStore
import com.archery.delaycam.camera.SegmentInfo
import com.archery.delaycam.settings.PictureMode
import java.io.File
import java.util.UUID

data class ViewerSegment(val id: Long, val info: SegmentInfo, val store: ImmutableSegmentStore)

/** Metadata only. Each HTTP reader obtains its own lease before accessing immutable media. */
class ViewerSegmentSource {
    private val entries = ArrayDeque<ViewerSegment>()
    private var epoch = UUID.randomUUID().toString()
    private var nextId = 0L
    private var active = false
    private var delaySec = 5
    private var mode = PictureMode.FIT

    @Synchronized fun reset() { entries.clear(); epoch = UUID.randomUUID().toString(); active = false }
    @Synchronized fun update(active: Boolean, delaySec: Int, mode: PictureMode) {
        this.active = active; this.delaySec = delaySec; this.mode = mode
    }
    @Synchronized fun publish(info: SegmentInfo, store: ImmutableSegmentStore) {
        if (entries.any { it.info.file == info.file }) return
        entries.addLast(ViewerSegment(nextId++, info, store))
        // Bounds metadata independently of camera settings and the number of viewers.
        while (entries.size > 72) entries.removeFirst()
    }
    @Synchronized fun acquire(id: Long): ImmutableSegmentStore.Lease? {
        val entry = entries.firstOrNull { it.id == id } ?: return null
        return entry.store.acquire(listOf(entry.info))
    }
    @Synchronized fun status(nowMs: Long = System.currentTimeMillis()): String {
        val media = if (active) entries.filter { it.info.file.isFile && it.info.file.length() > 0 } else emptyList()
        val segments = media.joinToString(",") {
            val s = it.info
            val aspect = s.contentAspectRatio?.takeIf { value -> value > 0 && value.isFinite() }
            "{\"id\":${it.id},\"startMs\":${s.startedAtMs},\"endMs\":${s.endedAtMs}," +
                "\"durationMs\":${s.mediaDurationMs},\"aspect\":${aspect ?: "null"}}"
        }
        return "{\"protocol\":1,\"epoch\":\"$epoch\",\"nowMs\":$nowMs,\"active\":$active," +
            "\"delaySec\":$delaySec,\"pictureMode\":\"${mode.name}\",\"segments\":[$segments]}"
    }
}

data class ByteWindow(val start: Long, val end: Long) { val length: Long get() = end - start + 1 }

/** One HTTP byte range, including Safari suffix probes. Invalid/multi ranges fail closed. */
fun parseByteRange(value: String?, length: Long): ByteWindow? {
    if (length <= 0) return null
    if (value == null) return ByteWindow(0, length - 1)
    val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(value) ?: return null
    val a = match.groupValues[1]; val b = match.groupValues[2]
    if (a.isEmpty()) {
        val count = b.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return ByteWindow((length - count).coerceAtLeast(0), length - 1)
    }
    val start = a.toLongOrNull()?.takeIf { it in 0 until length } ?: return null
    val end = if (b.isEmpty()) length - 1 else b.toLongOrNull() ?: return null
    if (end < start) return null
    return ByteWindow(start, minOf(end, length - 1))
}
