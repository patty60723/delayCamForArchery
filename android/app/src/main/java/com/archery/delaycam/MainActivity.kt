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
    private var lastLoggedRotationDegrees = -1
    private var imageAnalysis: ImageAnalysis? = null
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
                    // Bound ONCE for the Activity's whole lifetime, independent of which
                    // Compose screen is currently shown - see DelayCamViewModel.previewUseCase.
                    LaunchedEffect(Unit) { bindCameraOnce() }
                    DelayCamNavHost(viewModel = viewModel)
                } else {
                    CameraPermissionGate(onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    })
                }
            }
        }
    }

    private suspend fun bindCameraOnce() {
        if (cameraBound) return
        cameraBound = true
        try {
            val cameraProvider = awaitCameraProvider()

            val imageAnalysis = delayImageAnalysis()
            this.imageAnalysis = imageAnalysis
            updateCameraRotation()
            // One RGBA camera stream feeds the continuous encoder and a smaller
            // independent gesture image; no extra camera use case or JPEG conversion.
            imageAnalysis.setAnalyzer(viewModel.cameraAnalysisExecutor) { proxy ->
                try {
                    val sessionId = viewModel.cameraSessionForFrame() ?: return@setAnalyzer
                    // Reported "delayed view upside-down + both gestures dead"
                    // (they likely share one cause: gesture recognition reads
                    // the exact same bitmap the encoder draws, so a
                    // consistently-wrong rotation would break both at once).
                    // Logging the raw value CameraX reports rather than
                    // guessing a third time at front-camera rotation math -
                    // this is diagnostic only, the rotation logic itself is
                    // unchanged pending what this shows.
                    val rotationDegrees = proxy.imageInfo.rotationDegrees
                    if (rotationDegrees != lastLoggedRotationDegrees) {
                        lastLoggedRotationDegrees = rotationDegrees
                        Log.d(
                            "MainActivity",
                            "camera frame ${proxy.width}x${proxy.height}, format=${proxy.format}, target=1280x960 (720p crop), " +
                                "imageInfo.rotationDegrees=$rotationDegrees"
                        )
                    }
                    val bitmap = proxy.toRotatedBitmap()
                    val timestampMs = proxy.imageInfo.timestamp / 1_000_000L
                    try { viewModel.onCameraFrame(bitmap, timestampMs, sessionId) }
                    finally { bitmap.recycle() }
                } catch (e: Exception) {
                    Log.w("MainActivity", "frame conversion failed, skipping this frame", e)
                } finally {
                    proxy.close()
                }
            }

            // Front camera: the whole point of the delay mirror (spec 2.1) is the
            // archer looks at the screen like a mirror, which only reads correctly
            // if the camera facing them is the one doing the filming.
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                viewModel.previewUseCase,
                imageAnalysis,
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "CameraX bind failed", e)
            cameraBound = false
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

