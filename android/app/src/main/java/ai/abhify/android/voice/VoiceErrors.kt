package ai.abhify.android.voice

/**
 * 7.1.1: Android SpeechRecognizer error codes → one sentence that says what to do. Plain ints (the platform constants'
 * values) so the mapping is unit-tested on the JVM. Every code has a message; nothing here can throw.
 */
object VoiceErrors {
    const val NETWORK_TIMEOUT = 1; const val NETWORK = 2; const val AUDIO = 3; const val SERVER = 4; const val CLIENT = 5
    const val SPEECH_TIMEOUT = 6; const val NO_MATCH = 7; const val BUSY = 8; const val PERMISSIONS = 9; const val TOO_MANY = 10
    const val SERVER_DISCONNECTED = 11; const val LANGUAGE_NOT_SUPPORTED = 12; const val LANGUAGE_UNAVAILABLE = 13
    const val CANNOT_CHECK_SUPPORT = 14; const val CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15

    /** Failures of the on-device recogniser after which Android's standard recogniser is tried once instead. */
    val ON_DEVICE_FALLBACK = setOf(SERVER, CLIENT, SERVER_DISCONNECTED, LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE, CANNOT_CHECK_SUPPORT,
        CANNOT_LISTEN_TO_DOWNLOAD_EVENTS)

    const val NO_RECOGNIZER = "This phone has no speech recognizer. Install or enable \"Speech Recognition & Synthesis from Google\" (or another recognizer) in Android settings, or type your question."
    const val START_FAILED = "The microphone couldn't be started. Another app may be using it; close it and tap the microphone again."
    const val NOT_HEARD = "I didn't catch that. Tap the microphone and try again."

    fun message(code: Int): String = when (code) {
        NO_MATCH, SPEECH_TIMEOUT -> NOT_HEARD
        PERMISSIONS -> "Abhify needs the microphone permission. Allow it in Android Settings → Apps → Abhify → Permissions, then tap the microphone again."
        NETWORK, NETWORK_TIMEOUT -> "Speech recognition on this phone needs the internet (no offline speech pack is installed). Connect, or install an offline language in Android speech settings."
        AUDIO -> "The microphone couldn't record. Check that no other app is using it, then try again."
        BUSY -> "The speech recognizer is busy. Wait a moment and tap the microphone again."
        TOO_MANY -> "The speech service is getting too many requests. Wait a minute and try again."
        LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE -> "Speech recognition isn't available for this language on this phone. Choose English in Voice settings, or install the language in Android speech settings."
        SERVER, SERVER_DISCONNECTED, CLIENT, CANNOT_CHECK_SUPPORT, CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "The speech recognizer stopped unexpectedly. Tap the microphone to try again."
        else -> "Speech recognition failed (code $code). Tap the microphone to try again."
    }
}
