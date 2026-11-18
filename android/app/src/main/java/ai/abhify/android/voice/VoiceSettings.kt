package ai.abhify.android.voice

import org.json.JSONObject

/**
 * 5.4 Voice & Conversation settings (Settings → Voice & Conversation). Stored as one JSON value in the existing
 * preferences (no new store). Defaults follow the owner's decisions: female voice where the engine marks one, warm tone,
 * natural pace, English/Hindi, spoken + text, interruption on, tap-to-talk.
 */
data class VoiceSettings(
    val enabled: Boolean = true,
    /** The engine's voice name chosen by the user; null = automatic (a female-marked voice if the engine has one, else its default). */
    val voiceName: String? = null,
    val tone: Tone = Tone.WARM,
    val speed: Speed = Speed.NATURAL,
    val customRate: Float = 1.0f,
    val language: Language = Language.BOTH,
    val responseMode: ResponseMode = ResponseMode.SPOKEN_AND_TEXT,
    val interruptions: Boolean = true,
    /** After Abhify finishes speaking in a conversation, listen again automatically (natural turn-taking). */
    val autoListen: Boolean = true,
    val spokenNotifications: Boolean = false,
    val quietFrom: Int = 22, val quietTo: Int = 7,
    /** "What is this screen?" may use the name and purpose of the open Abhify screen (never a screenshot). */
    val screenHelp: Boolean = true,
) {
    enum class Tone(val label: String, val instruction: String) {
        WARM("Warm and friendly", "Speak warmly and kindly, like a helpful friend."),
        PROFESSIONAL("Professional", "Speak in a clear, professional and concise way."),
        CASUAL("Casual", "Speak casually and conversationally, in everyday words."),
        CALM("Calm and gentle", "Speak calmly and gently, at an unhurried pace."),
    }
    enum class Speed(val label: String, val rate: Float) { SLOW("Slow", 0.8f), NATURAL("Natural", 1.0f), FAST("Fast", 1.25f), CUSTOM("Custom", 1.0f) }
    enum class Language(val label: String) { ENGLISH("English"), HINDI("हिन्दी"), BOTH("English + हिन्दी"), AUTO("Automatic") }
    enum class ResponseMode(val label: String) { SPOKEN_AND_TEXT("Spoken + text"), VOICE_ONLY("Voice only"), TEXT_ONLY("Text only") }

    val rate get() = if (speed == Speed.CUSTOM) customRate.coerceIn(0.5f, 2.0f) else speed.rate
    val speaks get() = enabled && responseMode != ResponseMode.TEXT_ONLY

    /** The recogniser's language tag (Android recognises one language per request). Both/Automatic use the phone's language. */
    fun recognitionTag(deviceTag: String): String = when (language) { Language.ENGLISH -> "en-IN"; Language.HINDI -> "hi-IN"; else -> deviceTag }

    /** Instructions for answers given in a voice conversation: spoken style, tone and reply language. */
    fun conversationInstruction(): String = listOf(
        "This is a spoken conversation: answer in natural, short spoken sentences (no tables, no long lists, no code unless asked), " +
            "and offer to say more instead of reading everything at once.",
        tone.instruction,
        when (language) {
            Language.ENGLISH -> "Reply in English."
            Language.HINDI -> "Reply in Hindi (Devanagari script)."
            else -> "Reply in the language the user spoke (English or Hindi)."
        },
    ).joinToString(" ")

    /** Quiet hours, inclusive start, exclusive end; wraps past midnight. */
    fun isQuiet(hour: Int): Boolean = if (quietFrom == quietTo) false else if (quietFrom < quietTo) hour in quietFrom until quietTo else hour >= quietFrom || hour < quietTo

    fun toJson(): String = JSONObject().put("enabled", enabled).put("voiceName", voiceName ?: JSONObject.NULL).put("tone", tone.name).put("speed", speed.name)
        .put("customRate", customRate.toDouble()).put("language", language.name).put("responseMode", responseMode.name).put("interruptions", interruptions)
        .put("autoListen", autoListen).put("spokenNotifications", spokenNotifications).put("quietFrom", quietFrom).put("quietTo", quietTo)
        .put("screenHelp", screenHelp).toString()

    companion object {
        inline fun <reified E : Enum<E>> enumOr(v: String?, d: E): E = runCatching { enumValueOf<E>(v!!) }.getOrDefault(d)

        /** Missing or damaged JSON → defaults ([legacyRate]: the old reading-speed value, kept as a custom speed). */
        fun fromJson(json: String?, legacyRate: Float? = null): VoiceSettings {
            val d = VoiceSettings()
            val o = runCatching { JSONObject(json!!) }.getOrNull()
                ?: return if (legacyRate != null && legacyRate != 1.0f) d.copy(speed = Speed.CUSTOM, customRate = legacyRate) else d
            return VoiceSettings(o.optBoolean("enabled", true), if (o.isNull("voiceName")) null else o.optString("voiceName").ifBlank { null },
                enumOr(o.optString("tone"), d.tone), enumOr(o.optString("speed"), d.speed), o.optDouble("customRate", 1.0).toFloat(),
                enumOr(o.optString("language"), d.language), enumOr(o.optString("responseMode"), d.responseMode), o.optBoolean("interruptions", true),
                o.optBoolean("autoListen", true), o.optBoolean("spokenNotifications", false), o.optInt("quietFrom", 22).coerceIn(0, 23),
                o.optInt("quietTo", 7).coerceIn(0, 23), o.optBoolean("screenHelp", true))
        }

        /** A voice the engine itself marks as female (by name or feature); never guessed from anything else. */
        fun marksFemale(name: String, features: Set<String>): Boolean =
            Regex("(?i)(^|[^a-z])female([^a-z]|$)").containsMatchIn(name) || features.any { it.equals("female", true) || it.endsWith("#female", true) || it.contains("gender=female", true) }
        fun marksMale(name: String, features: Set<String>): Boolean =
            Regex("(?i)(^|[^a-z])male([^a-z]|$)").containsMatchIn(name) || features.any { it.equals("male", true) || it.endsWith("#male", true) || it.contains("gender=male", true) }

        /** Chooses the automatic voice: a female-marked voice for the language if any, else null (= the engine's default). */
        fun <V> autoVoice(voices: List<V>, name: (V) -> String, features: (V) -> Set<String>): V? = voices.firstOrNull { marksFemale(name(it), features(it)) }
    }
}
