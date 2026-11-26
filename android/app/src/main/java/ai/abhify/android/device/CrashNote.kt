package ai.abhify.android.device

import java.io.File

/**
 * 7.1.1: if the app ever crashes, the stack trace is written to the app's private storage (files/crash/last-crash.txt) so
 * the user can see and share it from Settings → Voice & Conversation → Last crash report. Local only: never uploaded,
 * no personal text (only the exception type, message and code locations). Android's normal crash handling still runs.
 */
object CrashNote {
    private const val DIR = "crash"
    private const val FILE = "last-crash.txt"

    fun install(filesDir: File, appVersion: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(filesDir, appVersion, thread.name, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The report text (pure, tested): version, time, thread, then the exception chain with up to 40 frames each. */
    fun format(appVersion: String, threadName: String, e: Throwable, now: Long = System.currentTimeMillis()): String = buildString {
        appendLine("Abhify.AI $appVersion crash report (stored only on this phone)")
        appendLine("time=${java.time.Instant.ofEpochMilli(now)} thread=$threadName android=${runCatching { android.os.Build.VERSION.SDK_INT }.getOrDefault(0)}")
        var t: Throwable? = e; var depth = 0
        while (t != null && depth < 5) {
            appendLine((if (depth == 0) "" else "Caused by: ") + t.javaClass.name + ": " + (t.message?.take(300) ?: ""))
            t.stackTrace.take(40).forEach { appendLine("    at $it") }
            t = t.cause; depth++
        }
        val log = VoiceLog.snapshot()
        if (log.isNotEmpty()) { appendLine("Last pipeline events (no personal text):"); log.takeLast(25).forEach { appendLine("  $it") } }
    }

    private fun write(filesDir: File, appVersion: String, threadName: String, e: Throwable) {
        val d = File(filesDir, DIR).apply { mkdirs() }
        File(d, FILE).writeText(format(appVersion, threadName, e))
    }

    fun last(filesDir: File): String? = File(File(filesDir, DIR), FILE).takeIf { it.isFile }?.readText()

    /**
     * 7.1.0: crashes the Java handler can't see (native code, Android closing the app for low memory, not responding)
     * are read from Android's own record of why the app last stopped (Android 11+) and saved the same way. Local only.
     */
    fun recordLastExit(context: android.content.Context, appVersion: String) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
            val prefs = context.getSharedPreferences("crash_note", android.content.Context.MODE_PRIVATE)
            if (info.timestamp <= prefs.getLong("seen", 0L)) return
            prefs.edit().putLong("seen", info.timestamp).apply()
            val kind = exitKind(info.reason) ?: return
            val trace = if (info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || info.reason == android.app.ApplicationExitInfo.REASON_ANR)
                runCatching { info.traceInputStream?.use { s -> s.readBytes().let { b -> String(b, 0, minOf(b.size, 24_000), Charsets.UTF_8) } } }.getOrNull() else null
            val text = buildString {
                appendLine("Abhify.AI $appVersion: the app last stopped because of: $kind (stored only on this phone)")
                appendLine("time=${java.time.Instant.ofEpochMilli(info.timestamp)} android=${android.os.Build.VERSION.SDK_INT} " +
                    "memory=${info.pss / 1024} MB (pss) ${info.rss / 1024} MB (rss)")
                info.description?.let { appendLine("description=${it.take(400)}") }
                VoiceLog.persisted(context.filesDir)?.let { appendLine("Last pipeline events before it stopped (no personal text):"); it.lines().takeLast(25).forEach { l -> appendLine("  $l") } }
                trace?.let { appendLine(it.take(24_000)) }
            }
            val d = File(context.filesDir, DIR).apply { mkdirs() }
            // a Java crash report written by the handler for the same stop is kept; this adds Android's view
            val existing = File(d, FILE).takeIf { it.isFile && it.lastModified() >= info.timestamp - 5_000 }?.readText()
            File(d, FILE).writeText(if (existing != null) existing + "\n" + text else text)
        }
    }

    /** Which exits are worth a report (pure, tested): crashes, native crashes, not responding, low memory. */
    fun exitKind(reason: Int): String? = when (reason) {
        4 -> "a crash"                                   // REASON_CRASH
        5 -> "a native crash (AI engine or another native library)"   // REASON_CRASH_NATIVE
        6 -> "the app stopped responding (ANR)"           // REASON_ANR
        3 -> "Android closed it to free memory"           // REASON_LOW_MEMORY
        else -> null
    }
    fun clear(filesDir: File) { File(File(filesDir, DIR), FILE).delete() }
}
