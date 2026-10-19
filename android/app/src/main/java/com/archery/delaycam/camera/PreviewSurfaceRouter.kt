package com.archery.delaycam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat

/** Keeps a draining Preview Surface alive when the Activity's TextureView disappears. Main thread only. */
class PreviewSurfaceRouter(context: Context, private var preview: Preview) {
    private val executor = ContextCompat.getMainExecutor(context)
    private var ui: Preview.SurfaceProvider? = null
    private var visible = false
    private var closed = false
    private var applied: Preview.SurfaceProvider? = null
    private val background = Preview.SurfaceProvider { request ->
        if (closed) request.willNotProvideSurface()
        else {
            val reader = ImageReader.newInstance(request.resolution.width, request.resolution.height, ImageFormat.PRIVATE, 3)
            reader.setOnImageAvailableListener({ source -> runCatching { source.acquireLatestImage()?.close() } }, Handler(Looper.getMainLooper()))
            request.provideSurface(reader.surface, executor) { reader.close() }
        }
    }

    fun setPreview(next: Preview) {
        if (preview === next) return
        preview.setSurfaceProvider(null)
        preview = next
        ui = null
        applied = null
        apply()
    }
    fun attach(candidate: Preview, provider: Preview.SurfaceProvider) {
        if (!closed && candidate === preview) { ui = provider; apply() }
    }
    fun detach(candidate: Preview, provider: Preview.SurfaceProvider) {
        if (candidate === preview && ui === provider) { ui = null; apply() }
    }
    fun setVisible(value: Boolean) { visible = value; apply() }
    private fun apply() {
        if (closed) return
        val target = if (visible) ui ?: background else background
        if (applied !== target) { applied = target; preview.setSurfaceProvider(executor, target) }
    }
    fun close() {
        closed = true
        ui = null
        preview.setSurfaceProvider(null)
        // CameraX releases each ImageReader through its provideSurface result callback.
    }
}
