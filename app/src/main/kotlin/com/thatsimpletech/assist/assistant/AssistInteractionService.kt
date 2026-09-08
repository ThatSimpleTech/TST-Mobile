package com.thatsimpletech.assist.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.thatsimpletech.assist.ui.MainActivity

/** Holder of the default-assistant role (plan §2). Long-press power and corner swipe land in [AssistSession]. */
class AssistInteractionService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/**
 * The assist gesture opens the app's entry screen and MainActivity starts on-device
 * listening. Screen context (onHandleAssist) is the M6 "what's on my screen" path and
 * is not consumed yet. Keyguard rule: answers only (plan §8) — MainActivity will not
 * auto-run a spoken goal while the phone is locked.
 */
class AssistSession(private val service: AssistSessionService) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(service, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_FROM_ASSIST, true)
        startAssistantActivity(intent)
        hide()
    }
}

/**
 * Required by the assistant role's metadata. Listening is [com.thatsimpletech.assist.voice.OnDeviceListen]
 * in the activity, not this service — implementing RecognitionService by wrapping another
 * SpeechRecognizer nests engines. Until sherpa-onnx owns this slot, every request reports
 * an error rather than pretending to listen.
 */
class AssistRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_SERVER)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
