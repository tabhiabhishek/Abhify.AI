package ai.abhify.android.settings

/**
 * v4.4 personal preferences that change presentation or the phone model's sampling only. Defaults reproduce the
 * behaviour before 4.4 exactly (temperature 0.3, system font size).
 */
object Personalization {
    /** How varied the phone model's answers are (online providers keep their own settings). */
    enum class Creativity(val label: String, val temperature: Float, val hint: String) {
        PRECISE("Precise", 0.2f, "Most consistent; best for facts, code and exact steps."),
        BALANCED("Balanced", 0.3f, "The default (same as before 4.4)."),
        CREATIVE("Creative", 0.7f, "More varied wording and ideas; may be less exact.");
        companion object { fun of(id: String?) = entries.firstOrNull { it.name == id } ?: BALANCED }
    }

    /** Text size inside Abhify, on top of the phone's own font size setting. */
    enum class TextSize(val label: String, val scale: Float) {
        SMALL("Small", 0.9f), DEFAULT("Default", 1.0f), LARGE("Large", 1.15f), LARGEST("Largest", 1.3f);
        companion object { fun of(id: String?) = entries.firstOrNull { it.name == id } ?: DEFAULT }
    }
}
