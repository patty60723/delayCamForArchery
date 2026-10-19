package com.archery.delaycam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.archery.delaycam.navigation.DelayCamNavHost
import com.archery.delaycam.service.ViewerForegroundService
import com.archery.delaycam.ui.DelayCamViewModel
import com.archery.delaycam.ui.theme.DelayCamTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as DelayCamApp
    val sessionModel: DelayCamViewModel get() = app.sessionModel()
    private var openViewerRequest by mutableIntStateOf(0)
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
        val displayId = window.decorView.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        app.camera.setRotation(displayManager.getDisplay(displayId)?.rotation ?: Surface.ROTATION_0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post { updateCameraRotation() }
    }
    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app.activityStarted(this)
        rotationHandler.post(rotationSync)
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        updateCameraRotation()
    }
    override fun onStop() {
        rotationHandler.removeCallbacks(rotationSync)
        displayManager.unregisterDisplayListener(displayListener)
        app.activityStopped(this)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() {
        app.activityDetached(this)
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeViewerIntent(intent)
    }
    private fun consumeViewerIntent(value: Intent) {
        if (value.getBooleanExtra(ViewerForegroundService.OPEN_VIEWER, false)) {
            openViewerRequest++
            value.removeExtra(ViewerForegroundService.OPEN_VIEWER)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.activityAttached(this)
        consumeViewerIntent(intent)
        setContent {
            var hasCameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
            val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                hasCameraPermission = granted
                app.refreshCameraOwner()
            }
            DelayCamTheme {
                if (hasCameraPermission) DelayCamNavHost(sessionModel, openViewerRequest)
                else CameraPermissionGate { permissionLauncher.launch(Manifest.permission.CAMERA) }
            }
        }
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

