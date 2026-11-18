package ai.abhify.android.ai

/**
 * 5.6 AI quality: per-provider reliability and speed for this app session. Counts and milliseconds only — never prompts,
 * answers, model output or anything typed. Kept in memory (cleared when the app process ends); nothing is sent anywhere.
 * Used by Settings → AI providers so the owner can compare providers and the offline fallback with real numbers.
 */
object RouteStats {
    data class Entry(val attempts: Int = 0, val failures: Int = 0, val fallbacks: Int = 0, val firstTokenMsTotal: Long = 0, val firstTokenCount: Int = 0,
                     val totalMs: Long = 0, val tokens: Long = 0) {
        val failureRate get() = if (attempts == 0) null else failures * 100 / attempts
        val avgFirstTokenMs get() = if (firstTokenCount == 0) null else firstTokenMsTotal / firstTokenCount
        val avgMs get() = if (attempts - failures <= 0) null else totalMs / (attempts - failures)
    }

    private val data = HashMap<Source.Kind, Entry>()

    /** One attempt on [kind]: [ok] = answered; [firstTokenMs] = time to first words (null if none); [fellBackFrom] = an earlier route failed first. */
    @Synchronized fun record(kind: Source.Kind, ok: Boolean, firstTokenMs: Long?, totalMs: Long, tokens: Int, fellBackFrom: Boolean) {
        val e = data[kind] ?: Entry()
        data[kind] = e.copy(attempts = e.attempts + 1, failures = e.failures + if (ok) 0 else 1, fallbacks = e.fallbacks + if (fellBackFrom && ok) 1 else 0,
            firstTokenMsTotal = e.firstTokenMsTotal + (firstTokenMs ?: 0), firstTokenCount = e.firstTokenCount + if (firstTokenMs != null) 1 else 0,
            totalMs = e.totalMs + if (ok) totalMs else 0, tokens = e.tokens + tokens)
    }

    @Synchronized fun snapshot(): Map<Source.Kind, Entry> = data.toMap()
    @Synchronized fun clear() = data.clear()

    /** One plain line per provider, e.g. "On this phone: 6 answers, 0 failed, first words after 1.4 s on average". */
    fun lines(s: Map<Source.Kind, Entry> = snapshot()): List<String> = s.entries.sortedBy { it.key.ordinal }.map { (k, e) ->
        "${k.label}: ${e.attempts - e.failures} answered, ${e.failures} failed" + (e.failureRate?.takeIf { e.failures > 0 }?.let { " ($it %)" } ?: "") +
            (e.avgFirstTokenMs?.let { ", first words after %.1f s on average".format(it / 1000.0) } ?: "") +
            (if (e.fallbacks > 0) ", ${e.fallbacks} as fallback" else "")
    }
}
