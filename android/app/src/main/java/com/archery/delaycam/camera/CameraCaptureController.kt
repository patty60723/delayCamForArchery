package com.archery.delaycam.camera

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.repeatOnLifecycle
import com.archery.delaycam.settings.CaptureAspect
import com.archery.delaycam.ui.DelayCamViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.coroutines.resume

/** Application-session CameraX owner. Activity visibility and a promoted camera FGS are separate reasons to run. */
class CameraCaptureController(private val application: Application, private val viewModel: DelayCamViewModel) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var provider: ProcessCameraProvider? = null
    private var cameraBound = false
    private var boundRequest: CaptureAspect? = null
    @Volatile private var imageAnalysis: ImageAnalysis? = null
    private var rotation = android.view.Surface.ROTATION_0
    init {
        registry.currentState = Lifecycle.State.CREATED
        scope.launch {
            viewModel.awaitInitialSettings()
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.settings.map { it.captureAspect }.distinctUntilChanged(), viewModel.recordingPhase) { aspect, phase -> aspect to phase }
                    .collect { (aspect, phase) -> if (!cameraBound || phase == RecordingPhase.IDLE) bindCamera(aspect) }
            }
        }
    }
    fun setActive(active: Boolean) {
        val allowed = active && ContextCompat.checkSelfPermission(application, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (registry.currentState != Lifecycle.State.DESTROYED)
            registry.currentState = if (allowed) Lifecycle.State.STARTED else Lifecycle.State.CREATED
    }
    fun setRotation(value: Int) { rotation = value; updateRotation() }
    private fun updateRotation() {
        imageAnalysis?.targetRotation = rotation
        viewModel.previewUseCase.targetRotation = rotation
    }
    fun close() {
        imageAnalysis?.clearAnalyzer()
        val analysis = imageAnalysis
        imageAnalysis = null
        registry.currentState = Lifecycle.State.DESTROYED
        scope.cancel()
        analysis?.let { provider?.unbind(it) }
        provider?.unbind(viewModel.previewUseCase)
    }
    private suspend fun bindCamera(requested: CaptureAspect) {
        if (cameraBound && boundRequest == requested) return
        viewModel.reportCameraSource(CameraSourceStatus(requested))
        try {
            val provider = awaitCameraProvider().also { this.provider = it }
            val oldAnalysis = imageAnalysis
            imageAnalysis = null
            oldAnalysis?.clearAnalyzer()
            oldAnalysis?.let { provider.unbind(it) }
            provider.unbind(viewModel.previewUseCase)
            cameraBound = false
            val (source, analysis) = bindPreferredSource(requested) { candidate ->
                val nextAnalysis = delayImageAnalysis(candidate)
                val nextPreview = cameraPreview(candidate)
                imageAnalysis = nextAnalysis
                viewModel.publishCameraPreview(nextPreview)
                updateRotation()
                installAnalyzer(nextAnalysis, requested, candidate)
                try {
                    provider.bindToLifecycle(this@CameraCaptureController, CameraSelector.DEFAULT_FRONT_CAMERA, nextPreview, nextAnalysis)
                    val size = nextAnalysis.resolutionInfo?.resolution
                    Log.i("MainActivity", "source request=$requested bound=$candidate actual=$size")
                    nextAnalysis
                } catch (failure: Exception) {
                    nextAnalysis.clearAnalyzer()
                    imageAnalysis = null
                    provider.unbind(nextPreview, nextAnalysis)
                    Log.w("MainActivity", "source request=$requested candidate=$candidate failed", failure)
                    throw failure
                }
            }
            imageAnalysis = analysis
            cameraBound = true
            boundRequest = requested
            viewModel.reportCameraSource(CameraSourceStatus(requested, source, switching = false))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            cameraBound = false
            viewModel.reportCameraSource(CameraSourceStatus(requested, switching = false, failed = true))
            Log.e("MainActivity", "Camera source negotiation failed", failure)
        }
    }

    private fun installAnalyzer(analysis: ImageAnalysis, requested: CaptureAspect, source: CaptureAspect) {
        var loggedRotation = -1
        analysis.setAnalyzer(viewModel.cameraAnalysisExecutor) { proxy ->
            try {
                // A detached analyzer must not submit stale source frames after a switch.
                if (imageAnalysis !== analysis) return@setAnalyzer
                val sessionId = viewModel.cameraSessionForFrame() ?: return@setAnalyzer
                val rotation = proxy.imageInfo.rotationDegrees
                if (rotation != loggedRotation) {
                    loggedRotation = rotation
                    Log.i("MainActivity", "camera frame ${proxy.width}x${proxy.height} format=${proxy.format} " +
                        "requested=$requested source=$source rotation=$rotation")
                }
                val bitmap = proxy.toRotatedBitmap()
                viewModel.onCameraFrame(bitmap, proxy.imageInfo.timestamp / 1_000_000L, sessionId)
                // Hardware Canvas may still be using the pixels: no immediate recycle().
            } catch (failure: Exception) {
                Log.w("MainActivity", "frame conversion failed, skipping this frame", failure)
            } finally { proxy.close() }
        }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(application)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(application))
        }
}
