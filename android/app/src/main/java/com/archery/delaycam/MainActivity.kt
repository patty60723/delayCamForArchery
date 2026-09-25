package com.archery.delaycam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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
import com.archery.delaycam.navigation.DelayCamNavHost
import com.archery.delaycam.ui.DelayCamViewModel
import com.archery.delaycam.ui.theme.DelayCamTheme
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private val viewModel: DelayCamViewModel by viewModels()
    private var cameraBound = false

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

            // Feeds ContinuousSegmentEncoder instead of CameraX's own
            // Recorder - see that class's doc comment for why: reusing one
            // Recorder for rapid stop()/start() segment cycling was
            // confirmed on-device to produce segments with genuinely
            // inconsistent H.264 SPS profile/level at their boundaries,
            // which is what was breaking playback. A second Preview use
            // case (not a VideoCapture) is the standard CameraX pattern for
            // handing camera frames to a Surface you don't own, like a
            // MediaCodec encoder's input surface - CameraX does not
            // auto-correct rotation for it the way PreviewView does, so
            // the SurfaceRequest's TransformationInfoListener result is
            // forwarded to setOrientationHint() explicitly.
            val encodingPreview = Preview.Builder().build()
            encodingPreview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { request ->
                val resolution = request.resolution
                val surface = viewModel.segmentEncoder.prepare(resolution.width, resolution.height)
                request.setTransformationInfoListener(ContextCompat.getMainExecutor(this)) { info ->
                    viewModel.segmentEncoder.setOrientationHint(info.rotationDegrees)
                }
                request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { }
            }

            val analysisExecutor = Executors.newSingleThreadExecutor()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(analysisExecutor) { proxy ->
                viewModel.gestureEngine.analyze(proxy)
            }

            // Front camera: the whole point of the delay mirror (spec 2.1) is the
            // archer looks at the screen like a mirror, which only reads correctly
            // if the camera facing them is the one doing the filming.
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                viewModel.previewUseCase,
                encodingPreview,
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
