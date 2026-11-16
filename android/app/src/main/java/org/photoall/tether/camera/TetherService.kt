package org.photoall.tether.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.photoall.tether.R
import org.photoall.tether.develop.DevelopQueue
import org.photoall.tether.ui.MainActivity

/**
 * Keeps the app alive while a camera is connected (screen off, another app in front), so
 * shots keep arriving and developing; its notification tells how far the roll is.
 */
class TetherService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (CameraHub.state != CameraHub.State.CONNECTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID, notification(this))
        } catch (e: Exception) {
            CameraHub.note("后台保持失败：${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "tether"
        @Volatile private var running = false

        /** Starts, refreshes or stops the service to follow the connection. */
        fun update(context: Context) {
            val app = context.applicationContext
            val connected = CameraHub.state == CameraHub.State.CONNECTED
            try {
                if (connected && !running) {
                    running = true
                    app.startForegroundService(Intent(app, TetherService::class.java))
                } else if (connected) {
                    app.getSystemService(NotificationManager::class.java).notify(ID, notification(app))
                } else if (running) {
                    running = false
                    app.stopService(Intent(app, TetherService::class.java))
                }
            } catch (e: Exception) {
                running = false
                CameraHub.note("后台服务：${e.message}")
            }
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "相机联机", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val model = CameraHub.device?.model ?: "相机"
            val active = DevelopQueue.active
            val text = buildString {
                append("本次已冲洗 ${DevelopQueue.developedThisSession} 张")
                if (active > 0) append(" · 处理中 $active")
                CameraHub.settings.battery?.let { append(" · 相机电量 $it%") }
            }
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("$model 已连接")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
