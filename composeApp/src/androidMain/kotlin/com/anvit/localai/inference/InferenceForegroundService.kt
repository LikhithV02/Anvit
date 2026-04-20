package com.anvit.localai.inference

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * A foreground service that keeps LLM inference alive when the user switches
 * to another app. Without this, Android's LMK (Low Memory Killer) or App
 * background restrictions will suspend or kill the inference coroutine.
 */
class InferenceForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "InferenceFgService"
        private const val CHANNEL_ID = "anvit_inference_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
                val intent = Intent(context, InferenceForegroundService::class.java)
                context.startForegroundService(intent)
                Log.d(TAG, "Foreground service start requested")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to start foreground service — continuing without it: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, InferenceForegroundService::class.java)
                context.stopService(intent)
                Log.d(TAG, "Foreground service stop requested")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to stop foreground service: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Anvit::InferenceWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) 
        }
        Log.d(TAG, "WakeLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
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
            "AI Inference",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps AI response generation running in the background"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anvit is thinking…")
            .setContentText("Tap to return to the app")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
