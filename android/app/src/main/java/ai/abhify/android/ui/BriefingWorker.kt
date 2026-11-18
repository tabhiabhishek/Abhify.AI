package ai.abhify.android.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Next build — optional briefing reminder: once a day at the chosen hour it posts "Your briefing is ready" (no content,
 * nothing read aloud, no AI or network work in the background; the briefing itself is composed when the user opens it).
 * Off until the user turns it on; skipped during quiet hours (Voice & Conversation settings).
 */
class BriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("abhify", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ON, false)) return Result.success()
        val quiet = ai.abhify.android.voice.VoiceSettings.fromJson(prefs.getString("voice_settings", null)).isQuiet(java.time.LocalTime.now().hour)
        if (quiet) return Result.success()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Personal briefing", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java).putExtra(EXTRA, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            nm.notify(ID, NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Abhify")
                .setContentText("Your briefing is ready. Open Abhify to see it.").setContentIntent(open).setAutoCancel(true).build())
        }
        return Result.success()
    }

    companion object {
        const val PREF_ON = "briefing_reminder"; const val PREF_HOUR = "briefing_hour"; const val EXTRA = "open_briefing"
        private const val WORK = "abhify-briefing"; private const val CHANNEL = "briefing"; private const val ID = 4310

        /** Minutes from [now] to the next [hour]:00. */
        fun delayMinutes(hour: Int, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Long {
            var next = now.toLocalDate().atTime(hour.coerceIn(0, 23), 0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return java.time.Duration.between(now, next).toMinutes()
        }

        fun schedule(context: Context, on: Boolean, hour: Int) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(WORK); return }
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BriefingWorker>(24, TimeUnit.HOURS).setInitialDelay(delayMinutes(hour), TimeUnit.MINUTES).build())
        }
    }
}
