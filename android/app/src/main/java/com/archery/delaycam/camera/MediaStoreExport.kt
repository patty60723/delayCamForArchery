package com.archery.delaycam.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Shared by ClipExporter (per-shot backtrack save) and
 * ContinuousRecordingSession (session recording): both end up with a local
 * MP4 file that needs to land in Movies/Archery via MediaStore (spec 6), and
 * both need to pick the video track out of a segment file when muxing.
 */
object MediaStoreExport {

    fun saveVideoToMediaStore(context: Context, source: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Archery")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert() returned null - is storage full?")

        try {
        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IOException("could not open output stream for $uri")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        if (resolver.update(uri, values, null, null) != 1) throw IOException("無法完成影片保存")
        return uri
        } catch (e: Exception) {
            // Failed copies remain retryable at the caller; remove incomplete gallery entries.
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
    }

    fun selectVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }
}

