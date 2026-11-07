package com.archery.delaycam

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.archery.delaycam.camera.CameraSourceStatus
import com.archery.delaycam.camera.RecordingPhase
import com.archery.delaycam.camera.bindPreferredSource
import com.archery.delaycam.camera.cameraPreview
import com.archery.delaycam.settings.CaptureAspect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.archery.delaycam.camera.toRotatedBitmap
import com.archery.delaycam.camera.delayImageAnalysis
import com.archery.delaycam.navigation.DelayCamNavHost
import com.archery.delaycam.ui.DelayCamViewModel
import com.archery.delaycam.ui.theme.DelayCamTheme
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private val viewModel: DelayCamViewModel by viewModels()
    private var cameraBound = false
    private var boundRequest: CaptureAspect? = null
    @Volatile private var imageAnalysis: ImageAnalysis? = null
    private val displayManager by lazy { applicationContext.getSystemService(DisplayManager::class.java) }
    private val rotationHandler = Handler(Looper.getMainLooper())
    private var lastAppliedRotation = -1
    private val rotationSync = object : Runnable {
        override fun run() {
            updateCameraRotation()
            // A 180-degree turn can notify listeners before the Display cache
            // catches up, without a later size/configuration callback.
            rotationHandler.postDelayed(this, 250L)
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (window.decorView.display?.displayId == displayId) {
                updateCameraRotation()
                window.decorView.post { updateCameraRotation() }
            }
        }
    }

    private fun updateCameraRotation() {
        // View.display can carry Activity resource overrides with the previous
        // rotation when a 180-degree turn has no public configuration difference.
        val displayId = window.decorView.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        val rotation = displayManager.getDisplay(displayId)?.rotation ?: Surface.ROTATION_0
        if (lastAppliedRotation != rotation) {
            Log.d("MainActivity", "camera target rotation=$rotation")
            lastAppliedRotation = rotation
        }
        imageAnalysis?.let { if (it.targetRotation != rotation) it.targetRotation = rotation }
        if (viewModel.previewUseCase.targetRotation != rotation) viewModel.previewUseCase.targetRotation = rotation
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Compose receives the new size; CameraX keeps its existing binding.
        // The display callback also covers a 180-degree turn without a size change.
        window.decorView.post { updateCameraRotation() }
    }

    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel.onAppForegrounded()
        rotationHandler.post(rotationSync)
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        updateCameraRotation()
    }

    override fun onStop() {
        rotationHandler.removeCallbacks(rotationSync)
        displayManager.unregisterDisplayListener(displayListener)
        viewModel.onAppBackgrounded()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onDestroy() {
        // The executor belongs to the retained ViewModel. Remove this
        // Activity's analyzer before a replacement Activity binds its own.
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var hasCameraPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                )
            }
            val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { granted -> hasCameraPermission = granted },
            )

            DelayCamTheme {
                if (hasCameraPermission) {
                    LaunchedEffect(Unit) {
                        viewModel.awaitInitialSettings()
                        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            combine(viewModel.settings.map { it.captureAspect }.distinctUntilChanged(),
                                viewModel.recordingPhase) { aspect, phase -> aspect to phase }
                                .collect { (aspect, phase) ->
                                    if (!cameraBound || phase == RecordingPhase.IDLE) bindCamera(aspect)
                                }
                        }
                    }
                    DelayCamNavHost(viewModel = viewModel)
                } else {
                    CameraPermissionGate(onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    })
                }
            }
        }
    }

    private suspend fun bindCamera(requested: CaptureAspect) {
        if (cameraBound && boundRequest == requested) return
        viewModel.reportCameraSource(CameraSourceStatus(requested))
        try {
            val provider = awaitCameraProvider()
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
                updateCameraRotation()
                installAnalyzer(nextAnalysis, requested, candidate)
                try {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, nextPreview, nextAnalysis)
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
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
        }
}

@Composable
private fun CameraPermissionGate(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text("需要相機權限才能使用延遲鏡子 - 請點一下畫面授權", color = Color.White)
    }
    LaunchedEffect(Unit) { onRequestPermission() }
}

