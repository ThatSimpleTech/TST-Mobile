package com.thatsimpletech.assist.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.thatsimpletech.assist.core.voice.VoiceCopy
import java.util.ArrayDeque

/**
 * On-device TTS of a finished run (M4). The engine is whatever the phone already has;
 * Kokoro lands later behind the same [speak] call. Safe from any thread.
 */
class OnDeviceSpeak(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()

    fun speak(text: String) {
        val line = VoiceCopy.spokenOutcome(text)
        if (line.isBlank()) return
        main.post {
            ensure()
            if (ready) say(line) else pending.addLast(line)
        }
    }

    fun shutdown() {
        main.post {
            pending.clear()
            tts?.stop()
            tts?.shutdown()
            tts = null
            ready = false
        }
    }

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                while (pending.isNotEmpty()) say(pending.removeFirst())
            }
        }
    }

    private fun say(line: String) {
        tts?.speak(line, TextToSpeech.QUEUE_FLUSH, null, "ezer-outcome")
    }
}
