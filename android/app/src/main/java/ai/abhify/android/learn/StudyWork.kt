package ai.abhify.android.learn

import ai.abhify.android.graph
import ai.abhify.android.ui.MainActivity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 7.2.0-dev.2 daily study reminder: off by default; once a day at the time the user picked (WorkManager, inexact) it
 * posts one notification with real counts from study.json ("3 flashcards and 2 missed questions due", "Next lesson: …").
 * Nothing due and no next lesson: no notification. Uses the already-declared POST_NOTIFICATIONS only; no AI, no network.
 */
class StudyReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.graph.prefs
        if (!prefs.getBoolean(PREF_ON, false)) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return Result.success()
        val s = runCatching { StudyAndroid.service(ctx, StudyAndroid.NO_AI, onSaved = {}).snapshot() }.getOrNull() ?: return Result.success()
        val text = StudyExtras.reminderText(s.cardsDue, s.questionsDue, s.nextLesson) ?: return Result.success()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Study reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 7201, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_STUDY, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            nm.notify(ID, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Time to study")
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
        }
        return Result.success()
    }

    companion object {
        const val PREF_ON = "study_reminder"; const val PREF_HOUR = "study_reminder_hour"; const val PREF_MINUTE = "study_reminder_minute"
        const val EXTRA_OPEN_STUDY = "open_study"
        private const val WORK = "abhify-study-reminder"; private const val CHANNEL = "study_reminders"; private const val ID = 7201

        /** Minutes from [now] to the next [hour]:[minute]. */
        fun delayMinutes(hour: Int, minute: Int, now: LocalDateTime = LocalDateTime.now()): Long {
            var next = now.toLocalDate().atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next).toMinutes()
        }

        fun schedule(context: Context, on: Boolean, hour: Int, minute: Int) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(WORK); return }
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<StudyReminderWorker>(24, TimeUnit.HOURS).setInitialDelay(delayMinutes(hour, minute), TimeUnit.MINUTES).build())
        }
    }
}

/**
 * 7.2.0-dev.2 offline lesson packs: off by default. While the phone is charging, idle and the battery isn't low, the next
 * (up to) 3 lessons of each course with a chosen goal are written by the model on this phone through the same lesson()
 * path, skipping lessons already written. It never downloads anything and stops when no model is installed. Writing ahead
 * doesn't count as study activity (no streak, no history).
 */
class LessonPackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        if (!g.prefs.getBoolean(PREF_ON, false)) return Result.success()
        if (g.store.active() == null) { record(g.prefs, 0, "No AI model is on this phone, so no lessons were written."); return Result.success() }
        val svc = StudyAndroid.service(applicationContext, StudyAndroid.backgroundAi(g), StudyAndroid.brainSource(g))
        val picks = runCatching { StudyExtras.packSelection(svc.packCourses()) }.getOrDefault(emptyList())
        var written = 0; var note: String? = if (picks.isEmpty()) "Nothing to write: choose a goal for a course, or every next lesson is already written." else null
        for ((cid, lid) in picks) {
            if (isStopped || g.store.active() == null) break
            try { if (!svc.lesson(cid, lid, prefetch = true).cached) written++ }
            catch (e: StudyError) { note = e.message; break }
        }
        record(g.prefs, written, note)
        return Result.success()
    }

    companion object {
        const val PREF_ON = "study_packs"; const val PREF_LAST = "study_pack_last"; const val PREF_COUNT = "study_pack_count"; const val PREF_NOTE = "study_pack_note"
        private const val WORK = "abhify-study-lesson-packs"

        private fun record(prefs: android.content.SharedPreferences, n: Int, note: String?) =
            prefs.edit().putLong(PREF_LAST, System.currentTimeMillis()).putInt(PREF_COUNT, n).putString(PREF_NOTE, note).apply()

        /** Charging + idle + battery not low; no network constraint (it runs on the phone and downloads nothing). */
        fun constraints(): Constraints = Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).setRequiresBatteryNotLow(true).build()

        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(WORK); return }
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LessonPackWorker>(12, TimeUnit.HOURS).setConstraints(constraints()).build())
        }
    }
}
