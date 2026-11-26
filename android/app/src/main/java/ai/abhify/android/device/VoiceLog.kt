package ai.abhify.android.device

import java.io.File

/**
 * 7.1.0: a small, local record of what the voice/answer pipeline did last (component + event + time), so a crash report
 * shows where it happened (microphone, recogniser, live information, model, speech). Never contains what the user
 * said, the AI's text, keys or audio: callers pass only component names, states and error kinds. Kept in memory and
 * written to files/crash/pipeline.log on each event, so it survives a native crash. Nothing is uploaded.
 */
object VoiceLog {
    private const val MAX = 60
    private val lines = ArrayDeque<String>()
    @Volatile private var file: File? = null

    fun init(filesDir: File) { file = File(File(filesDir, "crash").apply { mkdirs() }, "pipeline.log") }

    /** [component]: mic, recognizer, live, model, tts, wake, ui… [event]: short state or error kind (no personal text). */
    @Synchronized fun add(component: String, event: String) {
        val line = "${java.time.Instant.now()} $component: ${event.take(120)}"
        lines.addLast(line); while (lines.size > MAX) lines.removeFirst()
        runCatching { file?.writeText(lines.joinToString("\n")) }
    }

    @Synchronized fun snapshot(): List<String> = lines.toList()

    /** The last events written before the app stopped (also after a native crash). */
    fun persisted(filesDir: File): String? = File(File(filesDir, "crash"), "pipeline.log").takeIf { it.isFile }?.readText()
}
