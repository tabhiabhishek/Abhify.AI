package ai.abhify.android.ui

import ai.abhify.android.graph
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 5.6 per-to-do reminders: the user picks a time for one to-do. They are opt-in (none exist until the user sets one) and
 * use no new permission: the existing POST_NOTIFICATIONS and WorkManager (inexact, battery friendly). Quiet hours move a
 * reminder to the end of the quiet period. A completed or deleted to-do never notifies. The lock screen shows only
 * "To-do reminder"; the title appears once the phone is unlocked. Stored in filesDir/task_reminders.json, beside the
 * database, so the schema doesn't change.
 */
object TaskReminders {
    const val EXTRA_SNOOZE = "snooze_task"; const val EXTRA_OPEN = "open_task_reminder"
    private const val CHANNEL = "task_reminders"
    private fun work(id: Long) = "abhify-task-reminder-$id"
    private fun file(c: Context) = File(c.filesDir, "task_reminders.json")

    fun load(c: Context): Map<Long, Long> = runCatching { val o = JSONObject(file(c).readText())
        o.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to o.getLong(k) } }.toMap() }.getOrDefault(emptyMap())
    private fun save(c: Context, m: Map<Long, Long>) = runCatching {
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k.toString(), v) }
        val tmp = File(c.filesDir, "task_reminders.json.tmp"); tmp.writeText(o.toString()); if (!tmp.renameTo(file(c))) { file(c).delete(); tmp.renameTo(file(c)) }
    }

    /** If [at] falls in quiet hours ([from]–[to], local time), move it to [to]:00 at the end of that quiet period. */
    fun adjustForQuiet(at: LocalDateTime, from: Int, to: Int): LocalDateTime {
        if (from == to) return at
        val h = at.hour
        val quiet = if (from < to) h in from until to else h >= from || h < to
        if (!quiet) return at
        var end = at.toLocalDate().atTime(to, 0)
        if (!end.isAfter(at)) end = end.plusDays(1)
        return end
    }

    /** Preset times offered in the to-do dialog: in 1 hour, this evening 18:00 (if still ahead), tomorrow 09:00. */
    fun presets(now: LocalDateTime): List<Pair<String, LocalDateTime>> = listOfNotNull(
        "In 1 hour" to now.plusHours(1).withSecond(0).withNano(0),
        now.toLocalDate().atTime(18, 0).takeIf { it.isAfter(now.plusMinutes(10)) }?.let { "This evening 18:00" to it },
        "Tomorrow 09:00" to now.toLocalDate().plusDays(1).atTime(9, 0),
    )

    fun set(c: Context, taskId: Long, at: LocalDateTime): LocalDateTime {
        val s = ai.abhify.android.voice.VoiceSettings.fromJson(c.getSharedPreferences("abhify", Context.MODE_PRIVATE).getString("voice_settings", null))
        val when_ = adjustForQuiet(at, s.quietFrom, s.quietTo)
        val ms = when_.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        save(c, load(c) + (taskId to ms))
        WorkManager.getInstance(c).enqueueUniqueWork(work(taskId), ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<TaskReminderWorker>()
            .setInitialDelay((ms - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).setInputData(workDataOf("task" to taskId)).build())
        return when_
    }

    fun cancel(c: Context, taskId: Long) {
        WorkManager.getInstance(c).cancelUniqueWork(work(taskId))
        save(c, load(c) - taskId)
        runCatching { c.getSystemService(NotificationManager::class.java).cancel(notificationId(taskId)) }
    }

    /** Drops the stored time only (used after the reminder fired; the posted notification and running work are left alone). */
    fun forget(c: Context, taskId: Long) { save(c, load(c) - taskId) }

    fun snooze(c: Context, taskId: Long, minutes: Long = 60) = set(c, taskId, LocalDateTime.now().plusMinutes(minutes))

    fun notificationId(taskId: Long) = 50_000 + (taskId % 100_000).toInt()

    fun label(ms: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).let { "%02d:%02d, %d %s".format(it.hour, it.minute, it.dayOfMonth, it.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }) }

    internal fun channel(c: Context) = c.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CHANNEL, "To-do reminders", NotificationManager.IMPORTANCE_DEFAULT))

    internal fun post(c: Context, taskId: Long, title: String) {
        channel(c)
        fun pi(code: Int, extra: String) = PendingIntent.getActivity(c, code, Intent(c, MainActivity::class.java).putExtra(extra, taskId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Abhify").setContentText("To-do reminder").build()
        c.getSystemService(NotificationManager::class.java).notify(notificationId(taskId), NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("To-do reminder").setContentText(title.take(120))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setContentIntent(pi(notificationId(taskId), EXTRA_OPEN)).addAction(0, "Snooze 1 hour", pi(notificationId(taskId) + 1, EXTRA_SNOOZE))
            .setAutoCancel(true).build())
    }
}

class TaskReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val id = inputData.getLong("task", -1)
        if (id < 0 || id !in TaskReminders.load(c)) return Result.success()                 // cancelled meanwhile
        val row = runCatching { c.graph.db.readableDatabase.rawQuery("SELECT title, done FROM tasks WHERE id = ?", arrayOf(id.toString())).use { r ->
            if (r.moveToFirst()) r.getString(0) to (r.getInt(1) == 1) else null } }.getOrNull()
        if (row == null || row.second) { TaskReminders.forget(c, id); return Result.success() }  // deleted or completed: no notification
        val s = ai.abhify.android.voice.VoiceSettings.fromJson(c.getSharedPreferences("abhify", Context.MODE_PRIVATE).getString("voice_settings", null))
        if (s.isQuiet(LocalDateTime.now().hour)) { TaskReminders.set(c, id, LocalDateTime.now()); return Result.success() }   // moved to the end of quiet hours
        runCatching { TaskReminders.post(c, id, row.first) }
        TaskReminders.forget(c, id)                                                            // one-shot: clear the stored time (the notification stays)
        return Result.success()
    }
}
