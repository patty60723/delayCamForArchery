package com.archery.delaycam.camera

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Paused replay and asynchronous export must not read slots the camera will overwrite. */
class ClipSnapshot private constructor(val segments: List<SegmentInfo>, private val directory: File) {
    fun delete() { directory.deleteRecursively() }

    companion object {
        suspend fun copy(cacheDir: File, sources: List<SegmentInfo>): ClipSnapshot = withContext(Dispatchers.IO) {
            val directory = File(cacheDir, "shot_${UUID.randomUUID()}")
            check(directory.mkdirs()) { "無法建立影片暫存，請確認儲存空間" }
            try {
                val segments = sources.mapIndexed { index, segment ->
                    val target = File(directory, "$index.mp4")
                    segment.file.copyTo(target)
                    segment.copy(file = target)
                }
                ClipSnapshot(segments, directory)
            } catch (e: Exception) {
                directory.deleteRecursively()
                throw e
            }
        }
    }
}
