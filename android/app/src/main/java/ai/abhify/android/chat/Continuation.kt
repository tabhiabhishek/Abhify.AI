package ai.abhify.android.chat

/**
 * "Continue generating" for an answer that was cut off (output limit), stopped, or interrupted. The model gets the
 * conversation plus its own partial answer and is asked to go on; the new text is appended to the same message.
 * Models often repeat the last few words first, so the overlap is removed before joining.
 */
object Continuation {
    /** Statuses of a partial answer that can be continued. */
    val PARTIAL = setOf(Status.LENGTH, Status.STOPPED, Status.INTERRUPTED)
    /** Continuations per answer (no endless loops). */
    const val MAX = 3
    const val INSTRUCTION = "Continue your previous answer exactly where it stopped. Do not repeat anything you already wrote, " +
        "do not summarize, and do not add an introduction: start with the next words."

    object Status {
        const val LENGTH = "length"          // the output limit was reached
        const val STOPPED = "stopped"        // the user tapped Stop
        const val INTERRUPTED = "interrupted" // the connection or the model ended early
    }

    /** How a finished generation is stored: null = complete. */
    fun statusOf(textEmpty: Boolean, failed: Boolean, finished: Boolean, finish: String?): String? = when {
        textEmpty -> null                                   // nothing to store (the error is shown instead)
        failed -> Status.INTERRUPTED
        !finished -> Status.STOPPED                         // no Done event: the user stopped it
        finish == "length" -> Status.LENGTH
        finish == "cancelled" -> Status.STOPPED
        finish == "interrupted" -> Status.INTERRUPTED
        else -> null
    }

    /** Leading characters of [addition] that repeat the end of [existing] (at least [min] characters to count). */
    fun overlap(existing: String, addition: String, min: Int = 8): Int {
        val lead = addition.length - addition.trimStart().length
        val add = addition.substring(lead)
        val max = minOf(existing.length, add.length, 600)
        for (k in max downTo min) if (existing.endsWith(add.substring(0, k))) return lead + k
        return 0
    }

    /** The extended answer: [existing] followed by [addition] without the repeated part. */
    fun join(existing: String, addition: String): String {
        val drop = overlap(existing, addition)
        val rest = addition.substring(drop)
        return if (drop > 0) existing + rest else existing + rest
    }

    /** The end of a long partial answer, for the phone's small context window. */
    fun tail(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else "…" + text.substring(text.length - maxChars).let { t -> t.substring(t.indexOf(' ').coerceAtLeast(0)).trimStart() }
}
