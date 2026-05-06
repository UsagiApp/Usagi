package org.draken.usagi.core.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.draken.usagi.R

class BaseCrashService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stackTrace = intent?.getStringExtra(EXTRA_STACK_TRACE) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, createNotification())
        val dialogIntent = Intent(this, BaseCrashActivity::class.java).apply {
            putExtra(BaseCrashActivity.EXTRA_STACK_TRACE, stackTrace)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(dialogIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "crash_handler"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, getString(R.string.app_name),
				NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.error_occurred))
            .setContentText(getString(R.string.crash_text))
            .setSmallIcon(R.drawable.ic_alert_outline)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    companion object {
        const val EXTRA_STACK_TRACE = "stack_trace"
        private const val NOTIFICATION_ID = 22

        fun start(context: Context, stackTrace: String) {
            val intent = Intent(context, BaseCrashService::class.java).apply {
                putExtra(EXTRA_STACK_TRACE, stackTrace)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
