package com.archery.delaycam

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.archery.delaycam.camera.CameraCaptureController
import com.archery.delaycam.camera.RecordingPhase
import com.archery.delaycam.state.SavePhase
import com.archery.delaycam.ui.DelayCamViewModel
import kotlinx.coroutines.*

/** One capture session can outlive the Activity while a user-started foreground service owns it. */
class DelayCamApp : Application() {
    private val store = ViewModelStore()
    private var model: DelayCamViewModel? = null
    private var controller: CameraCaptureController? = null
    private val activities = mutableSetOf<Any>()
    private val started = mutableSetOf<Any>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var release: Job? = null
    var notificationPermissionAsked = false

    fun sessionModel(): DelayCamViewModel = model ?: ViewModelProvider(store,
        ViewModelProvider.AndroidViewModelFactory(this))[DelayCamViewModel::class.java].also { model = it }
    fun peekModel(): DelayCamViewModel? = model
    val camera: CameraCaptureController get() = controller ?: CameraCaptureController(this, sessionModel()).also { controller = it }
    fun activityAttached(token: Any) { release?.cancel(); activities.add(token); camera }
    fun activityStarted(token: Any) {
        val wasEmpty = started.isEmpty()
        started.add(token)
        if (wasEmpty) sessionModel().onAppForegrounded()
        refreshCameraOwner()
    }
    fun activityStopped(token: Any) {
        if (started.remove(token) && started.isEmpty()) model?.onAppBackgrounded()
        refreshCameraOwner()
    }
    fun activityDetached(token: Any) {
        activityStopped(token)
        activities.remove(token)
        releaseWhenUnused((token as? MainActivity)?.isChangingConfigurations == true)
    }
    fun refreshCameraOwner() {
        controller?.setActive(started.isNotEmpty() || model?.sharingServiceActive == true)
        model?.previewSurfaceRouter?.setVisible(started.isNotEmpty())
        releaseWhenUnused()
    }
    private fun releaseWhenUnused(replacing: Boolean = false) {
        if (activities.isNotEmpty() || model?.sharingServiceActive == true) { release?.cancel(); release = null; return }
        if (release?.isActive == true) return
        release = scope.launch {
            // Allow an Activity replacement to attach, and let any final recording save finish.
            if (replacing) delay(500)
            val current = model ?: return@launch
            while (current.recordingPhase.value != RecordingPhase.IDLE || current.recordingSave.value.phase == SavePhase.SAVING ||
                current.clipSave.value.phase == SavePhase.SAVING) delay(100)
            if (activities.isEmpty() && !current.sharingServiceActive && model === current) {
                controller?.close(); controller = null
                model = null
                store.clear()
            }
        }
    }
}
