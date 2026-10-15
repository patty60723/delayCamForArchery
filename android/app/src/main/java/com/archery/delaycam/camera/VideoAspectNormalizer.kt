package com.archery.delaycam.camera

import android.content.Context
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.effect.MatrixTransformation
import androidx.media3.common.util.Size
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.archery.delaycam.gallery.ClipPresentationStore
import com.archery.delaycam.gallery.PictureRange
import com.archery.delaycam.ui.playback.activePictureScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Normalize only at export; the rolling encoder remains continuous across camera rotation. */
object VideoAspectNormalizer {
    data class Result(val file: File, val ranges: List<PictureRange>)

    suspend fun normalize(context: Context, source: File, ranges: List<PictureRange>,
        requestedAspect: Float? = ranges.firstNotNullOfOrNull { it.aspect }): Result {
        val (width, height) = withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(source.absolutePath)
                val format = extractor.getTrackFormat(checkNotNull(MediaStoreExport.selectVideoTrack(extractor)))
                format.getInteger(MediaFormat.KEY_WIDTH) to format.getInteger(MediaFormat.KEY_HEIGHT)
            } finally { extractor.release() }
        }
        val encodedAspect = width.toFloat() / height
        val aspect = requestedAspect?.takeIf { it.isFinite() && it > 0f } ?: encodedAspect
        if (kotlin.math.abs(aspect - encodedAspect) < .001f)
            return Result(source, ranges)

        val size = captureSize(width, height, aspect)
        val output = File.createTempFile("aspect_export_", ".mp4", context.cacheDir)
        output.delete()
        try {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    // Use one matrix: separate scale/presentation effects clip at each stage
                    // and would discard visible pixels before the final output is sized.
                    val fitPicture = object : MatrixTransformation {
                        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(size.first, size.second)
                        override fun getMatrix(timeUs: Long): Matrix {
                            val contentAspect = ClipPresentationStore.aspectAt(ranges, timeUs / 1000L)
                            val zoom = activePictureScale(size.first.toFloat(), size.second.toFloat(), encodedAspect, contentAspect)
                            val outputAspect = size.first.toFloat() / size.second
                            return Matrix().apply { setScale(
                                minOf(1f, encodedAspect / outputAspect) * zoom,
                                minOf(1f, outputAspect / encodedAspect) * zoom) }
                        }
                    }
                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(source.toUri()))
                        .setEffects(Effects(emptyList(), listOf(fitPicture)))
                        .build()
                    val transformer = Transformer.Builder(context.applicationContext)
                        .setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .setMaxDelayBetweenMuxerSamplesMs(30_000L)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                                if (continuation.isActive) continuation.resumeWithException(exception)
                            }
                        }).build()
                    continuation.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { transformer.cancel(); output.delete() }
                    }
                    try { transformer.start(edited, output.absolutePath) }
                    catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                }
            }
            return Result(output, ranges.map { it.copy(aspect = aspect) })
        } catch (failure: Throwable) { output.delete(); throw failure }
    }
}
