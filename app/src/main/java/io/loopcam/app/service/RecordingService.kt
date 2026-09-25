package io.loopcam.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import io.loopcam.app.R
import io.loopcam.app.session.SessionController
import io.loopcam.core.audio.LoopConfig
import javax.inject.Inject

/** Mantiene viva la sesión aunque la app pase a segundo plano o se apague la pantalla. */
@AndroidEntryPoint
class RecordingService : LifecycleService() {

    @Inject lateinit var sessionController: SessionController

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                val seconds = intent.getDoubleExtra(EXTRA_LOOP_SECONDS, DEFAULT_LOOP_SECONDS)
                sessionController.start(LoopConfig(seconds))
            }
            ACTION_STOP -> {
                sessionController.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessionController.stop()
        super.onDestroy()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_recording))
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    companion object {
        private const val ACTION_START = "io.loopcam.action.START"
        private const val ACTION_STOP = "io.loopcam.action.STOP"
        private const val EXTRA_LOOP_SECONDS = "loop_seconds"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        const val DEFAULT_LOOP_SECONDS = 4.0

        fun start(context: Context, loopSeconds: Double) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LOOP_SECONDS, loopSeconds)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
