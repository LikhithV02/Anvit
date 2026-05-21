package com.anvit.localai.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps model downloads alive when the user backgrounds the app.
 * Without it, a multi-GB Gemma 4 download can be killed mid-flight by Android's process
 * restrictions, forcing the user to retry repeatedly even though `.part` resume works.
 */
class DownloadForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "DownloadFgService"
        private const val CHANNEL_ID = "anvit_download_channel_v1"
        private const val NOTIFICATION_ID = 1002

        @Volatile private var lastFraction: Float = 0f
        @Volatile private var lastStatus: String = "Starting download…"

        fun start(context: Context) {
            try {
                lastFraction = 0f
                lastStatus = "Starting download…"
                val intent = Intent(context, DownloadForegroundService::class.java)
                context.startForegroundService(intent)
                Log.d(TAG, "Foreground service start requested")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to start foreground service: ${e.message}")
            }
        }

        fun update(context: Context, fraction: Float, status: String) {
            lastFraction = fraction.coerceIn(0f, 1f)
            lastStatus = status
            try {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                manager.notify(NOTIFICATION_ID, buildNotification(context, lastFraction, lastStatus))
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to update notification: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, DownloadForegroundService::class.java)
                context.stopService(intent)
                Log.d(TAG, "Foreground service stop requested")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to stop foreground service: ${e.message}")
            }
        }

        private fun accentColor(context: Context): Int {
            val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (nightMode == Configuration.UI_MODE_NIGHT_YES) 0xFF00C8E8.toInt() else 0xFF0099BA.toInt()
        }

        private fun appIconBitmap(context: Context): Bitmap? = try {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            if (drawable is BitmapDrawable) drawable.bitmap
            else {
                val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
                bmp
            }
        } catch (_: Exception) { null }

        private fun buildNotification(context: Context, fraction: Float, status: String): Notification {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val tapIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.anvit.localai.R.drawable.ic_notification)
                .setColor(accentColor(context))
                .setContentTitle("Downloading models")
                .setContentText(status)
                .setContentIntent(tapIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Anvit::DownloadWakeLock"
        ).apply {
            acquire(60 * 60 * 1000L)
        }
        Log.d(TAG, "WakeLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(this, lastFraction, lastStatus),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else 0
            )
            Log.d(TAG, "Foreground service running")
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed — stopping service: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps model downloads running when the app is in the background"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
