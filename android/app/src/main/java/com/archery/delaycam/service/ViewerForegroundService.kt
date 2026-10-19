package com.archery.delaycam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.archery.delaycam.DelayCamApp
import com.archery.delaycam.MainActivity
import com.archery.delaycam.camera.RecordingPhase
import com.archery.delaycam.ui.DelayCamViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Started only by the visible App's explicit sharing action; never restarted after process death. */
class ViewerForegroundService : Service() {
    companion object {
        const val GENERATION = "viewer_generation"
        const val ACTION_STOP = "com.archery.delaycam.STOP_VIEWER"
        const val OPEN_VIEWER = "open_viewer"
        const val NOTIFICATION_ID = 2201
        private const val CHANNEL = "camera_viewer"
    }
    private val app get() = application as DelayCamApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var generation = -1L
    private var owner: DelayCamViewModel? = null
    private var promoted = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = app.peekModel()
        val requested = intent?.getLongExtra(GENERATION, -1L) ?: -1L
        if (intent?.action == ACTION_STOP) {
            if (model != null && requested == generation) model.stopViewerShare()
            if (!promoted) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (model == null || !model.canStartSharingService(requested)) {
            if (!promoted) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        owner = model
        generation = requested
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "拍攝與多裝置分享", NotificationManager.IMPORTANCE_LOW))
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(model),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
            promoted = true
            wake = (getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "箭影:多裝置分享")).apply {
                setReferenceCounted(false); acquire()
            }
            if (!model.sharingServiceStarted(this, requested)) { finishSharing(); return START_NOT_STICKY }
            observer?.cancel()
            observer = scope.launch {
                combine(model.viewerShare, model.recordingPhase) { _, _ -> Unit }.collect {
                    if (promoted) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(model))
                }
            }
        } catch (_: Exception) {
            model.sharingServiceFailed(requested)
            finishSharing()
        }
        return START_NOT_STICKY
    }
    private fun notification(model: DelayCamViewModel): Notification {
        val pending = model.viewerShare.value.requests.size
        val recording = model.recordingPhase.value == RecordingPhase.RECORDING
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(OPEN_VIEWER, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, generation.toInt(), Intent(this, ViewerForegroundService::class.java)
            .setAction(ACTION_STOP).putExtra(GENERATION, generation), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (pending > 0) "有 $pending 個觀看要求待確認" else "箭影正在拍攝與分享")
            .setContentText(when {
                pending > 0 -> "點此返回箭影核對代碼；拍攝與分享持續進行。"
                recording -> "整段錄影持續中；背景停止分享會結束錄影並保存。"
                else -> "切換 App 仍持續拍攝；點此返回箭影。"
            })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "停止分享", stop).build()
    }
    fun finishSharing() {
        promoted = false
        observer?.cancel()
        if (wake?.isHeld == true) wake?.release()
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { owner?.stopViewerShare(); finishSharing() }
    override fun onDestroy() {
        val oldOwner = owner
        finishSharing()
        scope.cancel()
        owner = null
        oldOwner?.sharingServiceDestroyed(this, generation)
        super.onDestroy()
    }
}
