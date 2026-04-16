package com.anvit.localai.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anvit.localai.MainActivity
import com.anvit.localai.R

/**
 * A foreground service that keeps LLM inference alive when the user switches
 * to another app. Without this, Android's LMK (Low Memory Killer) or App
 * background restrictions will suspend or kill the inference coroutine.
 *
 * Usage (from ViewModel or Activity):
 *   // Start before inference begins:
 *   InferenceForegroundService.start(context)
 *
 *   // Stop after inference completes or is cancelled:
 *   InferenceForegroundService.stop(context)
 */
class InferenceForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "InferenceFgService"
        private const val CHANNEL_ID = "anvit_inference_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, InferenceForegroundService::class.java)
            context.startForegroundService(intent)
            Log.d(TAG, "Foreground service start requested")
        }

        fun stop(context: Context) {
            val intent = Intent(context, InferenceForegroundService::class.java)
            context.stopService(intent)
            Log.d(TAG, "Foreground service stop requested")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Acquire a partial WakeLock so the CPU keeps running even if the
        // screen turns off mid-inference. Released in onDestroy().
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Anvit::InferenceWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10-minute timeout as a safety cap
        }
        Log.d(TAG, "WakeLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(TAG, "Foreground service running — inference protected from background kill")
        // START_STICKY: if the system kills the service, restart it without re-delivering intent
        return START_STICKY
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI Inference",
            NotificationManager.IMPORTANCE_LOW  // Low = silent, no sound
        ).apply {
            description = "Keeps AI response generation running in the background"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anvit is thinking…")
            .setContentText("Tap to return to the app")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with your app icon
            .setContentIntent(tapIntent)
            .setOngoing(true)        // Cannot be swiped away while active
            .setSilent(true)         // No sound/vibration
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
