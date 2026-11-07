package com.archery.delaycam.camera

import java.io.File

/** Trial capture files are never overwritten. Active readers pin them until finished. */
class ImmutableSegmentStore(private val directory: File, private val prefix: String) {
    private data class Entry(val file: File, var published: Boolean = false, var readers: Int = 0)
    private val entries = linkedMapOf<File, Entry>()
    private var sequence = 0L
    private var keepCount = 10
    private var closed = false

    class Lease internal constructor(
        val segments: List<SegmentInfo>, private val release: () -> Unit,
    ) : AutoCloseable {
        private var released = false
        @Synchronized override fun close() {
            if (!released) { released = true; release() }
        }
    }

    @Synchronized fun allocate(): File {
        check(!closed)
        return File(directory, "${prefix}_segment_${sequence++}.mp4").also {
            check(!it.exists())
            entries[it] = Entry(it)
        }
    }

    @Synchronized fun publish(file: File) {
        check(!closed)
        checkNotNull(entries[file]).published = true
        trim()
    }

    @Synchronized fun discard(file: File?) {
        if (file != null) { entries.remove(file); file.delete() }
    }

    @Synchronized fun setKeepCount(count: Int) {
        keepCount = count.coerceAtLeast(2)
        trim()
    }

    /** All-or-nothing acquisition also rejects files from another capture session. */
    @Synchronized fun acquire(segments: List<SegmentInfo>): Lease? {
        if (closed || segments.isEmpty()) return null
        val selected = segments.map { entries[it.file] ?: return null }.distinct()
        if (selected.any { !it.published || !it.file.isFile || it.file.length() == 0L }) return null
        selected.forEach { it.readers++ }
        return Lease(segments.toList()) { release(selected) }
    }

    @Synchronized private fun release(selected: List<Entry>) {
        selected.forEach { it.readers-- }
        trim()
    }

    @Synchronized fun close() { closed = true; trim() }

    private fun trim() {
        val retained = if (closed) emptySet() else entries.values.filter { it.published }
            .takeLast(keepCount).map { it.file }.toSet()
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.readers == 0 && (closed || entry.published && entry.file !in retained)) {
                entry.file.delete()
                iterator.remove()
            }
        }
    }
}
