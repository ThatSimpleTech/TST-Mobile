package com.thatsimpletech.assist.core.voice

/**
 * Maps [android.speech.SpeechRecognizer] error ints to a plain line. Numbers match the
 * framework constants so this file stays off `android.*` (core is pure JVM).
 *
 * On-device speech can still return NETWORK/SERVER codes when the pack is missing or
 * the engine crashes; those are not a socket we opened. Say so honestly.
 */
object VoiceErrors {
    const val NETWORK_TIMEOUT = 1
    const val NETWORK = 2
    const val AUDIO = 3
    const val SERVER = 4
    const val CLIENT = 5
    const val SPEECH_TIMEOUT = 6
    const val NO_MATCH = 7
    const val BUSY = 8
    const val PERMISSION = 9
    const val LANGUAGE_NOT_SUPPORTED = 12
    const val LANGUAGE_UNAVAILABLE = 13

    fun listen(code: Int): String = when (code) {
        PERMISSION -> "mic permission is off"
        NO_MATCH -> "heard nothing"
        SPEECH_TIMEOUT -> "waiting for speech timed out"
        BUSY -> "already listening"
        AUDIO -> "mic failed"
        LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE -> "this language is not on the phone"
        NETWORK, NETWORK_TIMEOUT, SERVER -> "on-device speech pack is missing or failed"
        CLIENT -> "could not start on-device speech"
        else -> "on-device speech failed ($code)"
    }
}
