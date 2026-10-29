
package com.example.pulsetimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class TimerService : Service() {

    companion object {
        const val ACTION_START = "com.example.pulsetimer.START"
        const val ACTION_PAUSE = "com.example.pulsetimer.PAUSE"
        const val ACTION_RESUME = "com.example.pulsetimer.RESUME"
        const val ACTION_RESET = "com.example.pulsetimer.RESET"
        const val ACTION_UPDATE = "com.example.pulsetimer.UPDATE"
        const val ACTION_FLASH = "com.example.pulsetimer.FLASH"

        private const val CHANNEL_ID = "pulse_timer_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var running = false
    private var remainingMs = 0L
    private var deadline = 0L
    private var nextCue = 0L

    private var soundEnabled = true
    private var flashEnabled = true
    private var volume = 25

    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = android.os.Handler(
        android.os.Looper.getMainLooper()
    )

    private val tickTask = object : Runnable {
        override fun run() {
            if (!running) return

            val now = SystemClock.elapsedRealtime()
            remainingMs = (deadline - now).coerceAtLeast(0L)

            while (running && now >= nextCue &&
                remainingMs > 0L) {
                signal()
                nextCue += 5000L
            }

            if (remainingMs <= 0L) {
                running = false
                remainingMs = 0L
                handler.removeCallbacks(this)

                signalFinished()
                releaseWakeLock()

                sendUpdate("Время вышло!")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            sendUpdate("Таймер работает")
            updateNotification()
            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                soundEnabled = intent.getBooleanExtra("sound", true)
                flashEnabled = intent.getBooleanExtra("flash", true)
                volume = intent.getIntExtra("volume", 25)
                    .coerceIn(0, 100)

                remainingMs = intent.getLongExtra("duration", 0L)
                    .coerceAtLeast(0L) * 1000L

                if (remainingMs <= 0L) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startAsForeground()
                beginCountdown()
            }

            ACTION_PAUSE -> pauseCountdown()
            ACTION_RESUME -> resumeCountdown()
            ACTION_RESET -> resetCountdown()
        }

        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification("Таймер запускается")

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginCountdown() {
        handler.removeCallbacks(tickTask)
        deadline = SystemClock.elapsedRealtime() + remainingMs
        nextCue = SystemClock.elapsedRealtime() + 5000L
        running = true

        acquireWakeLock()
        sendUpdate("Таймер работает")
        handler.post(tickTask)
    }

    private fun pauseCountdown() {
        if (!running) return

        remainingMs = (deadline -
            SystemClock.elapsedRealtime()).coerceAtLeast(0L)

        running = false
        handler.removeCallbacks(tickTask)
        releaseWakeLock()

        sendUpdate("Пауза")
        updateNotification()
    }

    private fun resumeCountdown() {
        if (running || remainingMs <= 0L) return

        beginCountdown()
    }

    private fun resetCountdown() {
        running = false
        handler.removeCallbacks(tickTask)
        remainingMs = 0L
        releaseWakeLock()

        sendUpdate("Сброшено")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun signal() {
        if (soundEnabled && volume > 0) {
            try {
                tone?.startTone(
                    ToneGenerator.TONE_PROP_BEEP,
                    110
                )
            } catch (_: Exception) {
            }
        }

        if (flashEnabled) {
            sendBroadcast(
                Intent(ACTION_FLASH).setPackage(packageName)
            )
        }
    }

    private fun signalFinished() {
        if (soundEnabled && volume > 0) {
            try {
                tone?.startTone(
                    ToneGenerator.TONE_PROP_ACK,
                    350
                )
            } catch (_: Exception) {
            }
        }

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                val manager = getSystemService(
                    VibratorManager::class.java
                )
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            180L,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(180L)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(POWER_SERVICE)
            as PowerManager

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PulseTimer::Countdown"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun sendUpdate(message: String) {
        val seconds = (remainingMs + 999L) / 1000L

        sendBroadcast(
            Intent(ACTION_UPDATE)
                .setPackage(packageName)
                .putExtra("remaining", seconds)
                .putExtra("status", message)
        )
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE)
            as NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                if (running) {
                    "Осталось: ${formatTime(remainingMs)}"
                } else {
                    "Пауза: ${formatTime(remainingMs)}"
                }
            )
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pulse Timer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление работающего таймера"
                setSound(null, null)
            }

            val manager = getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Pulse Timer")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun formatTime(ms: Long): String {
        val total = (ms + 999L) / 1000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60

        return "%02d:%02d:%02d".format(h, m, s)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tickTask)
        releaseWakeLock()
        tone?.release()
        tone = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
