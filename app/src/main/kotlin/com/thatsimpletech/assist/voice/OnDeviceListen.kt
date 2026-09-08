package com.thatsimpletech.assist.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.thatsimpletech.assist.core.voice.VoiceErrors
import java.util.Locale

/**
 * On-device dictation only (M4, plan §8). Uses createOnDeviceSpeechRecognizer
 * exclusively. The cloud factory is refused by a source scan. Audio does not leave
 * the phone. sherpa-onnx is a later swap behind this same callback shape.
 *
 * Must be used on the main thread.
 */
class OnDeviceListen(
    private val context: Context,
    private val onListening: () -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFail: (String) -> Unit,
) {
    private var sr: SpeechRecognizer? = null
    private var active = false

    val listening: Boolean get() = active

    fun start() {
        if (!available(context)) {
            onFail("on-device speech is not installed on this phone")
            return
        }
        stop()
        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            onFail("on-device speech is not installed on this phone")
            return
        }
        sr = recognizer
        recognizer.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        active = true
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            active = false
            destroy()
            onFail("could not start on-device speech")
        }
    }

    fun stop() {
        active = false
        destroy()
    }

    private fun destroy() {
        try {
            sr?.cancel()
        } catch (_: Exception) {
        }
        try {
            sr?.destroy()
        } catch (_: Exception) {
        }
        sr = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (active) onListening()
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            if (!active) return
            active = false
            destroy()
            onFail(VoiceErrors.listen(error))
        }
        override fun onResults(results: Bundle?) {
            if (!active) return
            active = false
            val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            destroy()
            if (said.isEmpty()) onFail(VoiceErrors.listen(VoiceErrors.NO_MATCH))
            else onFinal(said)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (!active) return
            val said = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (said.isNotEmpty()) onPartial(said)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        fun available(context: Context): Boolean =
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
}
