package ai.abhify.android.backup

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Scheduled backups (v3.5): off until the user turns them on. Android (WorkManager) decides the exact moment —
 * usually within an hour or so of the preferred time, later when the phone is in deep sleep or the battery is low —
 * so the app only ever says "about".
 */
data class BackupSchedule(
    val enabled: Boolean = false,
    val weekly: Boolean = false,                 // false = daily
    val hour: Int = 2,                           // preferred local hour (0–23)
    val weekday: Int = 7,                        // 1 = Monday … 7 = Sunday (weekly only)
    val categories: List<String> = listOf("Personal memory", "Conversations", "Notes and tasks"),
    val folderUri: String? = null,               // a folder the user picked (Storage Access Framework); null = none
    val folderName: String? = null,
    val keep: Int = 5,                           // retention per destination (1–30)
    val wifiOnly: Boolean = true,
    /** 7.2: this schedule used to upload to Google Drive (read from an older saved schedule; never saved again). Drive was removed as a destination. */
    val driveRemoved: Boolean = false,
) {
    fun json(): String = JSONObject().put("enabled", enabled).put("weekly", weekly).put("hour", hour).put("weekday", weekday)
        .put("categories", JSONArray(categories)).put("folderUri", folderUri ?: JSONObject.NULL)
        .put("folderName", folderName ?: JSONObject.NULL).put("keep", keep).put("wifiOnly", wifiOnly).toString()

    /** Why this can't be saved, or null. 7.2: scheduled backups go only to a folder on this phone (no automatic Drive uploads). */
    fun problem(hasPassphrase: Boolean, driveConnected: Boolean): String? = when {
        !enabled -> null
        categories.isEmpty() -> "Choose at least one thing to back up."
        categories.any { it !in Backup.CATEGORIES } -> "Unknown backup category."
        folderUri == null -> if (driveRemoved) "Scheduled backups no longer go to Google Drive (7.2: uploads only happen when you start them). Choose a folder on this phone."
            else "Choose a folder on this phone to save scheduled backups."
        hour !in 0..23 || weekday !in 1..7 -> "Choose a valid time."
        keep !in 1..30 -> "Keep between 1 and 30 backups."
        else -> null
    }

    /** The next preferred moment strictly after [now]. */
    fun nextRun(now: ZonedDateTime): ZonedDateTime {
        var t = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (weekly) {
            t = t.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(weekday)))
            if (!t.isAfter(now)) t = t.plusWeeks(1)
        } else if (!t.isAfter(now)) t = t.plusDays(1)
        return t
    }
    fun initialDelay(now: ZonedDateTime): Duration = Duration.between(now, nextRun(now))
    val periodHours: Long get() = if (weekly) 24L * 7 else 24L

    companion object {
        const val PREF = "backup_schedule"
        fun parse(s: String?): BackupSchedule = runCatching {
            val o = JSONObject(s!!)
            BackupSchedule(o.optBoolean("enabled"), o.optBoolean("weekly"), o.optInt("hour", 2), o.optInt("weekday", 7),
                o.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                o.optString("folderUri").takeIf { o.has("folderUri") && !o.isNull("folderUri") && it.isNotBlank() },
                o.optString("folderName").takeIf { o.has("folderName") && !o.isNull("folderName") && it.isNotBlank() },
                o.optInt("keep", 5).coerceIn(1, 30), o.optBoolean("wifiOnly", true), driveRemoved = o.optBoolean("toDrive"))
        }.getOrDefault(BackupSchedule())
    }
}

/**
 * Which scheduled backups may be removed. Only backups Abhify itself recorded as scheduled ([Owned]: made by the
 * schedule, written by Abhify, still present) are candidates; the newest [keep] (at least one) always stay. Manual
 * backups and anything Abhify didn't create are never returned.
 */
object Retention {
    data class Owned(val id: Long, val createdAt: Long, val auto: Boolean, val ok: Boolean)

    fun <T> select(items: List<T>, keep: Int, owned: (T) -> Owned): List<T> {
        val eligible = items.filter { owned(it).let { o -> o.auto && o.ok } }.sortedWith(compareByDescending<T> { owned(it).createdAt }.thenByDescending { owned(it).id })
        return eligible.drop(keep.coerceAtLeast(1))
    }
}
