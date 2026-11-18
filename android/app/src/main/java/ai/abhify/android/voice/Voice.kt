package ai.abhify.android.voice

import ai.abhify.android.chat.Markdown
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speech in and out with Android's own engines. Recognition prefers the fully on-device recognizer
 * (Android 12+, when the phone has one); otherwise Android's default recognizer is used with
 * "prefer offline", which may still send audio to Google's service. The UI says which one is active.
 */
class Voice(private val context: Context) {
    enum class Mode { ON_DEVICE, PREFER_OFFLINE, NONE }

    val mode: Mode = when {
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> Mode.ON_DEVICE
        SpeechRecognizer.isRecognitionAvailable(context) -> Mode.PREFER_OFFLINE
        else -> Mode.NONE
    }

    val description: String get() = when (mode) {
        Mode.ON_DEVICE -> "Speech is recognized on this phone (no internet)."
        Mode.PREFER_OFFLINE -> "Speech uses Android's recognizer; it may use Google's online service unless an offline language pack is installed."
        Mode.NONE -> "This phone has no speech recognizer."
    }

    private var recognizer: SpeechRecognizer? = null

    /** Calls [onPartial] while you speak and [onResult] / [onError] at the end. Main thread only. */
    /** 5.4: the microphone level (0..1) while listening, from the recogniser's real RMS; drives the waveform. */
    var onLevel: (Float) -> Unit = {}

    fun listen(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit, languageTag: String = Locale.getDefault().toLanguageTag()) {
        stopListening()
        val r = when (mode) {
            Mode.ON_DEVICE -> if (Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else return onError("unavailable")
            Mode.PREFER_OFFLINE -> SpeechRecognizer.createSpeechRecognizer(context)
            Mode.NONE -> return onError("This phone has no speech recognizer.")
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) { onResult(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()); stopListening() }
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial) }
            override fun onError(error: Int) {
                onError(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs the internet on this phone (no offline speech pack)."
                    else -> "Speech recognition error ($error)."
                })
                stopListening()
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) { onLevel(((v + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        })
    }

    fun stopListening() { recognizer?.let { runCatching { it.stopListening(); it.destroy() } }; recognizer = null }
    /** Abandons the recording without a result. */
    fun cancelListening() { recognizer?.let { runCatching { it.cancel(); it.destroy() } }; recognizer = null }

    // ------------------------------------------------------------------ text to speech
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Called with true while speech plays and false when it ends or stops; [onError] when the phone has no TTS engine. */
    var onSpeaking: (Boolean) -> Unit = {}
    var onSpeakError: (String) -> Unit = {}

    /** Who started the current reading ("chat", "news"): each screen only controls and shows its own. */
    @Volatile var owner: String? = null; private set
    /** Called with (part index, part count) when a part starts: News shows "item 2 of 6". */
    var onProgress: (Int, Int) -> Unit = { _, _ -> }
    /** Reading speed (Android TTS rate, 1.0 = normal). */
    var rate = 1.0f; private set
    @Volatile private var pending: List<String>? = null             // asked for while the engine was still starting

    fun speak(markdown: String) = speakParts(parts(Markdown.toSpeech(markdown), MAX_PART), "chat")   // the whole answer, in parts


    /** Reads [parts] in order (one utterance each). A new call replaces what was playing: never two voices. */
    fun speakParts(parts: List<String>, owner: String) {
        val list = parts.filter { it.isNotBlank() }
        if (list.isEmpty()) return
        this.owner = owner
        val t = tts
        if (t != null && ttsReady) { start(t, list); return }
        pending = list
        ensureEngine()
    }

    /**
     * 5.6 streaming speech: adds [parts] after what is already queued for the same [owner] (QUEUE_ADD, ids continue), so a
     * voice answer is spoken sentence by sentence while it is generated, in order and never in two voices. If nothing of
     * this owner is playing, it starts like [speakParts].
     */
    fun appendParts(parts: List<String>, owner: String) {
        val list = parts.filter { it.isNotBlank() }
        if (list.isEmpty()) return
        val t = tts
        if (t == null || !ttsReady) { if (this.owner == owner && pending != null) pending = pending.orEmpty() + list else speakParts(list, owner); return }
        if (reader.parts.isEmpty() || this.owner != owner || reader.paused) { speakParts(list, owner); return }
        val from = reader.parts.size
        reader = reader.copy(parts = reader.parts + list)
        for ((k, p) in list.withIndex()) if (t.speak(p, TextToSpeech.QUEUE_ADD, null, "abhify-${from + k}") != TextToSpeech.SUCCESS) { onSpeakError("Text-to-speech failed."); return }
    }

    /** Runs [then] once the speech engine is ready (initialising it if needed). */
    private val waiting = mutableListOf<(TextToSpeech) -> Unit>()
    fun withEngine(then: (TextToSpeech) -> Unit) { val t = tts; if (t != null && ttsReady) then(t) else { waiting += then; ensureEngine() } }

    private fun ensureEngine() {
        val t = tts
        if (t != null) return                                          // still initialising: it plays the latest request once ready
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            val engine = tts
            if (ttsReady && engine != null) {
                // 5.4: speech goes out as the assistant (Bluetooth/wired headsets follow Android's routing)
                engine.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) { id?.removePrefix("abhify-")?.toIntOrNull()?.let { reader = reader.started(it); onProgress(it, reader.parts.size) }; onSpeaking(true) }
                    override fun onDone(id: String?) {
                        if (id == "abhify-preview") { releaseFocus(); onSpeaking(false); return }
                        if (id?.removePrefix("abhify-")?.toIntOrNull() == reader.parts.lastIndex) { reader = Reader(); releaseFocus(); onSpeaking(false) } }
                    override fun onStop(id: String?, interrupted: Boolean) { releaseFocus(); onSpeaking(false) }
                    @Deprecated("API compatibility") override fun onError(id: String?) { onSpeaking(false); onSpeakError("Text-to-speech failed.") }
                })
                if (engine.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) engine.setLanguage(Locale.US)
                waiting.toList().forEach { it(engine) }; waiting.clear()
                pending?.let { start(engine, it) }; pending = null
            } else { tts = null; pending = null; waiting.clear(); onSpeakError("This phone has no text-to-speech engine. Install one (for example Speech Services by Google) in Android settings.") }
        }
    }

    // ------------------------------------------------------------------ 5.4 voice choice (only the engine's real voices)
    data class VoiceInfo(val name: String, val languageTag: String, val female: Boolean, val male: Boolean, val needsNetwork: Boolean, val quality: Int)

    /** The engine's installed voices for [language] ("en" / "hi"), best first. Gender only where the engine marks it. */
    fun voicesFor(language: String, then: (List<VoiceInfo>) -> Unit) = withEngine { t ->
        val list = runCatching { t.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == language && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .map { v -> val f = v.features.orEmpty()
                VoiceInfo(v.name, v.locale.toLanguageTag(), VoiceSettings.marksFemale(v.name, f), VoiceSettings.marksMale(v.name, f), v.isNetworkConnectionRequired, v.quality) }
            .sortedWith(compareByDescending<VoiceInfo> { it.female }.thenBy { it.needsNetwork }.thenByDescending { it.quality }.thenBy { it.name })
        then(list)
    }

    /** The voice chosen in Settings (null = automatic: a female-marked voice for the language if the engine has one, else its default). */
    @Volatile var preferredVoice: String? = null

    private fun applyVoice(t: TextToSpeech, hindi: Boolean) {
        val lang = if (hindi) "hi" else (Locale.getDefault().language.takeIf { it == "en" || it == "hi" } ?: "en")
        val voices = runCatching { t.voices.orEmpty() }.getOrDefault(emptySet()).filter { it.locale.language == lang &&
            !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val chosen = voices.firstOrNull { it.name == preferredVoice }
            ?: VoiceSettings.autoVoice(voices.sortedBy { it.isNetworkConnectionRequired }, { it.name }, { it.features.orEmpty() })
        if (chosen != null) runCatching { t.voice = chosen }
    }

    /** Speaks a short sample with [voiceName] (or automatic) at [rate], without changing the saved choice. */
    fun preview(sample: String, voiceName: String?, rate: Float) = withEngine { t ->
        val keep = preferredVoice; preferredVoice = voiceName
        t.setLanguage(if (isHindi(sample)) Locale("hi", "IN") else Locale.getDefault()); applyVoice(t, isHindi(sample))
        t.setSpeechRate(rate.coerceIn(0.5f, 2f)); t.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "abhify-preview")
        preferredVoice = keep; t.setSpeechRate(this.rate)
    }

    // ------------------------------------------------------------------ 5.4 audio focus while speaking (other audio ducks briefly)
    private val audio by lazy { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    private val focus by lazy { android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        // 5.6: a phone call or another app taking audio stops Abhify's speech (resumable), instead of talking over it
        .setOnAudioFocusChangeListener { c -> if (c == android.media.AudioManager.AUDIOFOCUS_LOSS || c == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pauseSpeaking() }
        .build() }
    fun takeFocus() { runCatching { audio.requestAudioFocus(focus) } }
    fun releaseFocus() { runCatching { audio.abandonAudioFocusRequest(focus) } }

    private fun start(t: TextToSpeech, parts: List<String>) {
        // 5.3: Hindi text is read with a Hindi voice, never with an English one; if none is installed, say so instead
        val hindi = parts.count(::isHindi) * 2 > parts.size
        val ok = t.setLanguage(if (hindi) Locale("hi", "IN") else Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE
        if (!ok && hindi) { reader = Reader(); onSpeaking(false)
            onSpeakError("No Hindi voice on this phone. Install it in Android settings → Text-to-speech → Speech Services by Google → Install voice data → Hindi."); return }
        if (!ok) t.setLanguage(Locale.US)
        applyVoice(t, hindi)
        takeFocus()
        reader = Reader(parts); t.setSpeechRate(rate); sayFrom(t, 0)
    }

    private fun sayFrom(t: TextToSpeech, from: Int) {
        for (i in from..reader.parts.lastIndex) {
            val r = t.speak(reader.parts[i], if (i == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "abhify-$i")
            if (r != TextToSpeech.SUCCESS) { onSpeakError("Text-to-speech failed."); return }
        }
    }

    /** Jumps [delta] parts (items) back or forward from the one playing, and keeps reading from there. */
    fun skip(delta: Int) { val t = tts ?: return; if (reader.parts.isEmpty()) return; reader = reader.copy(paused = false); sayFrom(t, (reader.current + delta).coerceIn(0, reader.parts.lastIndex)) }

    /** New speed; the part playing restarts at that speed. */
    fun setRate(r: Float) { rate = r.coerceIn(0.5f, 2f); val t = tts ?: return; t.setSpeechRate(rate); if (reader.parts.isNotEmpty() && !reader.paused) sayFrom(t, reader.resumeFrom) }

    /**
     * What is being read: the parts, the one playing, and whether it is paused. Android's TTS can't pause mid-sentence,
     * so Pause stops and Resume starts again at the beginning of the part that was playing.
     */
    data class Reader(val parts: List<String> = emptyList(), val current: Int = 0, val paused: Boolean = false) {
        fun started(i: Int) = copy(current = i, paused = false)
        fun pause() = if (parts.isEmpty()) this else copy(paused = true)
        val resumeFrom get() = current.coerceIn(0, (parts.size - 1).coerceAtLeast(0))
    }
    @Volatile private var reader = Reader()
    val paused get() = reader.paused

    /** Stops speech but remembers the place. */
    fun pauseSpeaking(): Boolean { if (reader.parts.isEmpty()) return false; reader = reader.pause(); tts?.stop(); onSpeaking(false); return true }
    fun resumeSpeaking() { val t = tts ?: return; if (reader.paused) sayFrom(t, reader.resumeFrom) }

    companion object {
        /** Mostly Devanagari letters (Hindi) — decides the voice (5.3). */
        fun isHindi(text: String): Boolean { val letters = text.count { it.isLetter() }; return letters > 0 && text.count { it in 'ऀ'..'ॿ' } * 2 >= letters }
        const val MAX_PART = 400

        /**
         * A news briefing as read-aloud parts: one per bullet/paragraph (so Next/Previous move by item), long items split at
         * sentences. Citation numbers like [1], links and Markdown are not read.
         */
        fun newsParts(title: String, markdown: String): List<String> =
            (listOf(title) + markdown.split(Regex("\\n\\s*\\n|\\n(?=\\s*(?:[-*•]|\\d+[.)])\\s)")))
                .map { Markdown.toSpeech(it.replace(Regex("\\s*https?://\\S+"), "").replace(Regex("\\s*\\[\\d+(?:\\s*[,–-]\\s*\\d+)*]"), "")).replace(Regex("\\s+"), " ").trim() }
                .filter { s -> s.any { it.isLetterOrDigit() } }
                .flatMap { parts(it, MAX_PART) }

        /** Splits text at sentence or word boundaries into pieces of at most [max] characters (nothing is dropped). */
        fun parts(text: String, max: Int): List<String> {
            val out = mutableListOf<String>(); var rest = text.trim()
            while (rest.length > max) {
                val cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), max).takeIf { it > max / 2 }?.plus(1)
                    ?: rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
                out += rest.substring(0, cut).trim(); rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }
    }

    fun stopSpeaking() { reader = Reader(); pending = null; owner = null; tts?.stop(); releaseFocus(); onSpeaking(false) }

    fun release() { stopListening(); tts?.shutdown(); tts = null; ttsReady = false }
}
