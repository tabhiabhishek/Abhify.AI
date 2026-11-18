package ai.abhify.android.device

import android.app.ActivityManager
import android.content.Context

/**
 * Processing profiles for documents and OCR. An 8 GB phone typically has 2–4 GB free with other apps open, and the
 * local model alone needs about 1.5 GB, so heavy work is sized to what is free now, not to the RAM on the box.
 * Each value changes real behaviour (see where it is read).
 */
enum class MemoryProfile(
    val label: String,
    /** Longest side of an image given to OCR (pixels). Larger = sharper small print, more memory (4 bytes/pixel). */
    val ocrMaxSide: Int,
    /** Scanned pages OCR'd per document; further pages are reported as skipped. Text-layer pages are always read. */
    val maxOcrPages: Int,
    /** PDF page render resolution for OCR (dots per inch), still capped by [ocrMaxSide]. */
    val renderDpi: Int,
    /** Characters of a document given to the phone's model (its context is small; online budgets are separate). */
    val localDocChars: Int,
) {
    LOW("Low memory", 1600, 8, 150, 2500),
    BALANCED("Balanced", 2200, 25, 200, 3500),
    QUALITY("Quality", 3000, 40, 300, 3500),           // same local budget: the model context isn't increased without testing
}

/** What Android reports about memory right now. */
data class MemorySnapshot(val totalMb: Int, val availMb: Int, val thresholdMb: Int, val lowMemory: Boolean, val lowRamDevice: Boolean,
                          val heapClassMb: Int, val largeHeapClassMb: Int)

object MemoryPolicy {
    /** Free memory under which only the Low profile is used. */
    const val LOW_AVAIL_MB = 1800
    /** Free memory needed before Quality is honoured (otherwise Balanced is used and the user is told). */
    const val QUALITY_AVAIL_MB = 3000
    /** Free memory below which no new OCR job starts. */
    const val MIN_OCR_AVAIL_MB = 500

    /**
     * The profile to use now. [choice] null = Auto. Memory pressure (a recent trim callback or Android's low-memory
     * flag) always forces Low, whatever was chosen.
     */
    fun pick(s: MemorySnapshot, choice: MemoryProfile?, pressure: Boolean): Pair<MemoryProfile, String?> = when {
        pressure || s.lowMemory -> MemoryProfile.LOW to "Android reports memory pressure, so the Low memory profile is used."
        s.lowRamDevice || s.availMb < LOW_AVAIL_MB -> MemoryProfile.LOW to
            (if (choice != null && choice != MemoryProfile.LOW) "Only ${s.availMb} MB free, so the Low memory profile is used." else null)
        choice == MemoryProfile.QUALITY && s.availMb < QUALITY_AVAIL_MB -> MemoryProfile.BALANCED to "Quality needs about $QUALITY_AVAIL_MB MB free (${s.availMb} MB now); Balanced is used."
        choice != null -> choice to null
        else -> MemoryProfile.BALANCED to null
    }

    /** Rough peak for OCR of one image: the decoded bitmap (4 bytes/px), one working copy, and the recognizer (~60 MB). */
    fun ocrPeakMb(width: Int, height: Int): Int = (width.toLong() * height * 4 * 2 / (1024 * 1024)).toInt() + 60

    /** Target size for decoding an image whose longest side must not exceed [maxSide]; never upscales. */
    fun fit(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= maxSide) return width to height
        val f = maxSide.toDouble() / long
        return maxOf(1, (width * f).toInt()) to maxOf(1, (height * f).toInt())
    }

    /** Render size of a PDF page (points, 1/72 inch) at [dpi], capped by [maxSide]. */
    fun pageRender(widthPt: Int, heightPt: Int, dpi: Int, maxSide: Int): Pair<Int, Int> =
        fit(maxOf(1, widthPt * dpi / 72), maxOf(1, heightPt * dpi / 72), maxSide)

    fun read(context: Context): MemorySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val mb = 1024 * 1024
        return MemorySnapshot((mi.totalMem / mb).toInt(), (mi.availMem / mb).toInt(), (mi.threshold / mb).toInt(), mi.lowMemory, am.isLowRamDevice,
            am.memoryClass, am.largeMemoryClass)
    }
}

/**
 * One heavy AI job at a time: OCR or the phone's language model, never both (their memory would add up).
 * Pure state machine so it is unit-tested; AppGraph owns the single instance.
 */
class HeavyGate {
    enum class Kind(val label: String) { OCR("text recognition"), LOCAL_AI("the phone's AI") }
    @Volatile var running: Kind? = null; private set
    /** Set by memory-pressure callbacks; new heavy jobs are refused until it clears. */
    @Volatile var pressureUntil: Long = 0

    fun underPressure(now: Long = System.currentTimeMillis()) = now < pressureUntil
    fun markPressure(forMs: Long = 60_000, now: Long = System.currentTimeMillis()) { pressureUntil = now + forMs }

    /** Starts [kind] or says why it can't start now. */
    @Synchronized fun tryEnter(kind: Kind, now: Long = System.currentTimeMillis()): String? {
        running?.let { other -> return if (other == kind) "${kind.label.replaceFirstChar(Char::uppercase)} is already running. Wait for it or cancel it."
            else "Please wait: ${other.label} is running. Heavy jobs run one at a time on this phone to keep memory safe." }
        if (kind == Kind.OCR && underPressure(now)) return "The phone is low on memory right now. Close other apps, wait a moment and retry."
        running = kind
        return null
    }

    @Synchronized fun exit(kind: Kind) { if (running == kind) running = null }
}
