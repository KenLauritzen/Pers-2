package com.focusledger.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * Keeps the ongoing tracker notification alive, handles the midnight split,
 * and fires the "still running" reminder based on time since the active
 * timer was last started.
 */
class TimerForegroundService : Service() {

    companion object {
        const val ACTION_REFRESH = "com.focusledger.timer.SERVICE_REFRESH"
        const val ACTION_STOP = "com.focusledger.timer.SERVICE_STOP"
        const val ACTION_STOP_TIMER = "com.focusledger.timer.SERVICE_STOP_TIMER"
        const val ACTION_CONTINUE = "com.focusledger.timer.SERVICE_CONTINUE"
        const val ACTION_STOP_AND_EXIT = "com.focusledger.timer.SERVICE_STOP_AND_EXIT"

        private const val CHANNEL_ONGOING = "focus_ledger_tracking"
        private const val CHANNEL_REMINDER = "focus_ledger_reminder"
        private const val CHANNEL_REMINDER_SILENT = "focus_ledger_reminder_silent"
        private const val NOTIF_ONGOING = 1001
        private const val NOTIF_REMINDER = 1002
        private const val TICK_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastKnownDay = -1

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (TimerStore.isRunning(this@TimerForegroundService)) {
                checkMidnightSplit()
                checkReminder()
                updateOngoing()
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        lastKnownDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TIMER -> {
                TimerStore.stop(this)
                cancelReminder()
            }
            ACTION_CONTINUE -> {
                TimerStore.resetReminderBase(this)
                cancelReminder()
            }
            ACTION_STOP_AND_EXIT -> {
                TimerStore.stop(this)
                TimerStore.endSession(this)
                shutDown(); return START_NOT_STICKY
            }
            ACTION_STOP -> {
                shutDown(); return START_NOT_STICKY
            }
        }

        if (!TimerStore.isRunning(this)) {
            shutDown(); return START_NOT_STICKY
        }

        startForeground(NOTIF_ONGOING, buildOngoing())
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_MS)
        return START_STICKY
    }

    private fun shutDown() {
        handler.removeCallbacks(tickRunnable)
        cancelReminder()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** If the day rolled over while a timer ran, split the run at midnight. */
    private fun checkMidnightSplit() {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (lastKnownDay != -1 && today != lastKnownDay) {
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            TimerStore.splitAtMidnight(this, midnight)
            cancelReminder()
        }
        lastKnownDay = today
    }

    private fun checkReminder() {
        val intervalMin = SettingsStore.getReminderMinutes(this)
        if (intervalMin <= 0) return
        val elapsedMin = TimerStore.msSinceReminderBase(this) / 60000
        if (elapsedMin >= intervalMin) {
            showReminder(elapsedMin.toInt())
            // Push the base forward so it re-fires one full interval later
            // even if the user ignores this one.
            TimerStore.resetReminderBase(this)
        }
    }

    private fun showReminder(elapsedMin: Int) {
        val label = TimerStore.getActiveLabel(this)
        if (label == TimerStore.NONE) return

        val channel = if (SettingsStore.isHeadsUp(this)) CHANNEL_REMINDER else CHANNEL_REMINDER_SILENT
        val priority = if (SettingsStore.isHeadsUp(this))
            NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW

        val b = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("$label has been running for $elapsedMin minutes")
            .setContentText("Keep going, or stop this timer?")
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(0, "Continue", servicePI(ACTION_CONTINUE, 11))
            .addAction(0, "Stop $label", servicePI(ACTION_STOP_TIMER, 12))

        val timeoutSec = SettingsStore.getReminderTimeoutSec(this)
        if (timeoutSec > 0) b.setTimeoutAfter(timeoutSec * 1000L)

        if (SettingsStore.isHeadsUp(this)) b.setDefaults(0)

        getSystemService(NotificationManager::class.java).notify(NOTIF_REMINDER, b.build())
    }

    private fun cancelReminder() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_REMINDER)
    }

    private fun updateOngoing() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ONGOING, buildOngoing())
    }

    private fun buildOngoing(): Notification {
        val active = TimerStore.getActiveLabel(this)
        val library = LabelStore.readLibrary(this).filter { it.visible }

        val title = if (active != TimerStore.NONE)
            "Running: $active  ${TimerStore.formatDuration(TimerStore.getDayMs(this, active))}"
        else getString(R.string.app_name_full)

        val content = library.joinToString("  \u00b7  ") { e ->
            "${e.name} ${TimerStore.formatDuration(TimerStore.getDayMs(this, e.name))}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(0, "Stop", servicePI(ACTION_STOP_TIMER, 1))
            .addAction(0, "Stop & exit", servicePI(ACTION_STOP_AND_EXIT, 2))
            .build()
    }

    private fun servicePI(action: String, code: Int): PendingIntent {
        val i = Intent(this, TimerForegroundService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Timer tracking", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Persistent elapsed-time display"
                    setShowBadge(false)
                }
        )

        // Heads-up channel. Sound is controlled by the user setting; because a
        // channel's sound is fixed at creation, we honour the toggle by
        // choosing between two channels.
        val headsUp = NotificationChannel(
            CHANNEL_REMINDER, "Timer reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Pops up when a timer has run for your set interval"
            if (!SettingsStore.isSoundOn(this@TimerForegroundService)) {
                setSound(null, null)
                enableVibration(false)
            } else {
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI, null)
            }
        }
        nm.createNotificationChannel(headsUp)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER_SILENT, "Timer reminders (silent)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows quietly in the notification shade"
                setSound(null, null)
            }
        )
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
