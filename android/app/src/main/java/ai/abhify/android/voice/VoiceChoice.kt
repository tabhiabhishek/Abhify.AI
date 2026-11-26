package ai.abhify.android.voice

/**
 * 7.1.1 Android output voice: exactly three choices (Male, Female, Assistant), resolved against the voices the phone's
 * speech engine really has installed. Nothing is guessed: gender only where the engine marks it, a voice the engine says
 * is not installed is never offered, and when a choice has no matching voice the result says so and falls back to the
 * engine's default voice instead of showing a broken option. Pure (unit-tested on the JVM).
 */
object VoiceChoice {
    /** One installed voice as the engine reports it. */
    data class Candidate(val name: String, val languageTag: String, val female: Boolean, val male: Boolean,
                         val needsNetwork: Boolean, val quality: Int, val isDefault: Boolean = false)

    /** [voiceName] null = let the engine use its default voice. [matched] = the choice found its own kind of voice. */
    data class Resolution(val voiceName: String?, val matched: Boolean, val note: String)

    /** Best first: works offline, then the engine's quality rating, then the name (stable). */
    private val best = compareBy<Candidate> { it.needsNetwork }.thenByDescending { it.quality }.thenBy { it.name }

    fun resolve(style: VoiceSettings.Style, voices: List<Candidate>, picked: String? = null): Resolution {
        voices.firstOrNull { it.name == picked }?.let { v ->
            return Resolution(v.name, true, "The voice you chose for ${style.label}" + if (v.needsNetwork) " (it uses the internet)." else ".") }
        if (voices.isEmpty()) return Resolution(null, false,
            "No voice for this language is installed in the phone's speech engine. Add one in Android Settings → Text-to-speech; until then answers are shown as text.")
        return when (style) {
            VoiceSettings.Style.MALE -> pick(voices.filter { it.male }, "male", voices)
            VoiceSettings.Style.FEMALE -> pick(voices.filter { it.female }, "female", voices)
            VoiceSettings.Style.ASSISTANT -> {
                val v = voices.filter { it.isDefault && !it.needsNetwork }.minWithOrNull(best) ?: voices.minWithOrNull(best)!!
                Resolution(v.name, true, "Your speech engine's best installed voice, at a natural, friendly pace" + if (v.needsNetwork) " (this voice uses the internet)." else ".")
            }
        }
    }

    private fun pick(matches: List<Candidate>, kind: String, all: List<Candidate>): Resolution {
        val v = matches.minWithOrNull(best)
        if (v != null) return Resolution(v.name, true, "A voice your speech engine marks as $kind" + if (v.needsNetwork) " (it uses the internet)." else ".")
        val fallback = all.filter { it.isDefault }.minWithOrNull(best) ?: all.minWithOrNull(best)
        return Resolution(fallback?.name, false, "This phone's speech engine doesn't say which of its voices is $kind, so its default voice is used. " +
            "Tap \"Choose voice\" below, listen to the voices and pick one for this choice.")
    }

    /** Older saved styles (7.1.0-dev.2 had nine) → the nearest of the three. */
    fun fromLegacy(name: String?): VoiceSettings.Style = when (name) {
        "MALE", "DEEP", "ROBOT" -> VoiceSettings.Style.MALE
        "FEMALE", "SOFT" -> VoiceSettings.Style.FEMALE
        else -> VoiceSettings.Style.ASSISTANT
    }
}
