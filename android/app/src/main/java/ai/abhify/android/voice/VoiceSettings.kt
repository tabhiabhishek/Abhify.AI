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
    /** 7.1.1: the output voice, exactly one of three (resolved against the installed voices by [VoiceChoice]). */
    val style: Style = Style.ASSISTANT,
    /** 7.1.1: listen for the assistant's name while Abhify is open on screen (off by default; on-device recogniser only). */
    val wakeWord: Boolean = false,
    /** 7.1.0: the installed voice the user picked for each choice (null = automatic). Needed on phones whose speech engine
     *  doesn't say which voices are male or female (then all three would otherwise sound the same). */
    val maleVoice: String? = null,
    val femaleVoice: String? = null,
    val assistantVoice: String? = null,
) {
    fun picked(style: Style): String? = when (style) { Style.MALE -> maleVoice; Style.FEMALE -> femaleVoice; Style.ASSISTANT -> assistantVoice }
    fun withPicked(style: Style, name: String?): VoiceSettings = when (style) {
        Style.MALE -> copy(maleVoice = name); Style.FEMALE -> copy(femaleVoice = name); Style.ASSISTANT -> copy(assistantVoice = name) }
    /** 7.1.1: Male, Female or Assistant. Real installed voices only; no pitch effects (they made voices sound robotic). */
    enum class Style(val label: String, val gender: String?) {
        MALE("Male", "male"),
        FEMALE("Female", "female"),
        ASSISTANT("Assistant", null),
    }

    /** 7.1.0 (owner): a soft, friendly tone: a touch warmer than flat, never an effect. */
    val pitch get() = SOFT_PITCH

    enum class Tone(val label: String, val instruction: String) {
        WARM("Warm and friendly", "Speak softly, warmly and kindly, like a good friend chatting: short natural sentences, a gentle tone, never stiff or robotic."),
        PROFESSIONAL("Professional", "Speak in a clear, professional and concise way."),
        CASUAL("Casual", "Speak casually and conversationally, in everyday words."),
        CALM("Calm and gentle", "Speak calmly and gently, at an unhurried pace."),
    }
    enum class Speed(val label: String, val rate: Float) { SLOW("Slow", 0.8f), NATURAL("Natural", 1.0f), FAST("Fast", 1.25f), CUSTOM("Custom", 1.0f) }
    enum class Language(val label: String) { ENGLISH("English"), HINDI("हिन्दी"), BOTH("English + हिन्दी"), AUTO("Automatic") }
    enum class ResponseMode(val label: String) { SPOKEN_AND_TEXT("Spoken + text"), VOICE_ONLY("Voice only"), TEXT_ONLY("Text only") }

    /** 7.1.0 (owner): a calm pace for a soft, friendly voice (Natural ≈ 0.92× the engine's speed). */
    val rate get() = ((if (speed == Speed.CUSTOM) customRate else speed.rate) * SOFT_RATE).coerceIn(0.5f, 2.0f)
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
        .put("screenHelp", screenHelp).put("style", style.name).put("wakeWord", wakeWord)
        .put("maleVoice", maleVoice ?: JSONObject.NULL).put("femaleVoice", femaleVoice ?: JSONObject.NULL).put("assistantVoice", assistantVoice ?: JSONObject.NULL).toString()

    companion object {
        const val SOFT_PITCH = 1.05f
        const val SOFT_RATE = 0.92f
        inline fun <reified E : Enum<E>> enumOr(v: String?, d: E): E = runCatching { enumValueOf<E>(v!!) }.getOrDefault(d)
        private fun str(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.optString(k).ifBlank { null }?.take(200)

        /** Missing or damaged JSON → defaults ([legacyRate]: the old reading-speed value, kept as a custom speed). */
        fun fromJson(json: String?, legacyRate: Float? = null): VoiceSettings {
            val d = VoiceSettings()
            val o = runCatching { JSONObject(json!!) }.getOrNull()
                ?: return if (legacyRate != null && legacyRate != 1.0f) d.copy(speed = Speed.CUSTOM, customRate = legacyRate) else d
            // 7.1.1: the voice comes from the three choices only; a voice picked by name in older versions is no longer used
            return VoiceSettings(o.optBoolean("enabled", true), null,
                enumOr(o.optString("tone"), d.tone), enumOr(o.optString("speed"), d.speed), o.optDouble("customRate", 1.0).toFloat(),
                enumOr(o.optString("language"), d.language), enumOr(o.optString("responseMode"), d.responseMode), o.optBoolean("interruptions", true),
                o.optBoolean("autoListen", true), o.optBoolean("spokenNotifications", false), o.optInt("quietFrom", 22).coerceIn(0, 23),
                o.optInt("quietTo", 7).coerceIn(0, 23), o.optBoolean("screenHelp", true), VoiceChoice.fromLegacy(o.optString("style").ifBlank { null }), o.optBoolean("wakeWord", false),
                str(o, "maleVoice"), str(o, "femaleVoice"), str(o, "assistantVoice"))
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
