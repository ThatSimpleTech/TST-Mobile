package com.thatsimpletech.assist.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.config.RunPrefs
import com.thatsimpletech.assist.task.GoalStart
import com.thatsimpletech.assist.ui.MainActivity
import com.thatsimpletech.assist.voice.OnDeviceListen

/** Holder of the default-assistant role (plan §2). Long-press power and corner swipe land in [AssistSession]. */
class AssistInteractionService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/**
 * Assist gesture is a listen sheet over the current app (TM-030). It does not open
 * MainActivity unless the microphone is off (permission has to be asked there).
 * Keyguard: hear, do not run (plan §8).
 */
class AssistSession(private val service: AssistSessionService) : VoiceInteractionSession(service) {
    private val main = Handler(Looper.getMainLooper())
    private val hideLater = Runnable { hide() }
    private var listen: OnDeviceListen? = null
    private var status: TextView? = null
    private var heard: TextView? = null
    private var closed = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        main.removeCallbacks(hideLater)
        stopListen()
        closed = false
        setUiEnabled(true)
        val ctx = context ?: service
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startAssistantActivity(MainActivity.listenIntent(service))
            hide()
            return
        }
        if (!OnDeviceListen.available(ctx)) {
            setContentView(panel(ctx, "On-device speech is missing"))
            finishSoon("on-device speech is not installed on this phone", speak = true)
            return
        }
        setContentView(panel(ctx, "Listening…"))
        startListen(ctx)
    }

    override fun onHide() {
        stopListen()
        super.onHide()
    }

    override fun onDestroy() {
        main.removeCallbacks(hideLater)
        stopListen()
        super.onDestroy()
    }

    private fun startListen(ctx: Context) {
        val session = OnDeviceListen(
            context = ctx.applicationContext,
            onListening = { main.post { if (!closed) status?.text = "Listening…" } },
            onPartial = { said -> main.post { if (!closed) heard?.text = said } },
            onFinal = { said -> main.post { onHeard(said) } },
            onFail = { msg -> main.post { finishSoon(msg, speak = true) } },
        )
        listen = session
        session.start()
    }

    private fun onHeard(said: String) {
        if (closed) return
        heard?.text = said
        val ctx = context ?: service
        val line = GoalStart.run(ctx.applicationContext, said)
        status?.text = line
        finishSoon(line, speak = false, delayMs = 900)
    }

    private fun finishSoon(msg: String, speak: Boolean, delayMs: Long = 1400) {
        if (closed) return
        closed = true
        stopListen()
        status?.text = msg
        if (speak && RunPrefs.speak(service)) Graph.voice.speak(msg)
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, delayMs)
    }

    private fun cancel() {
        finishSoon("cancelled", speak = false, delayMs = 0)
    }

    private fun stopListen() {
        listen?.stop()
        listen = null
    }

    private fun panel(ctx: Context, initial: String): View {
        val dp = ctx.resources.displayMetrics.density
        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(90, 0, 0, 0))
            isClickable = true
            setOnClickListener { cancel() }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 24, 24, 28))
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            isClickable = true
        }
        card.addView(
            TextView(ctx).apply {
                text = "EZER"
                textSize = 13f
                setTextColor(Color.LTGRAY)
            },
        )
        status = TextView(ctx).apply {
            text = initial
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, (6 * dp).toInt(), 0, (4 * dp).toInt())
        }
        heard = TextView(ctx).apply {
            textSize = 16f
            setTextColor(Color.LTGRAY)
        }
        card.addView(status)
        card.addView(heard)
        card.addView(
            Button(ctx).apply {
                text = "Cancel"
                setOnClickListener { cancel() }
            },
        )
        frame.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        return frame
    }
}

/**
 * Required by the assistant role's metadata. Listening is [OnDeviceListen] in the
 * session sheet (or MainActivity Talk). Implementing RecognitionService by wrapping
 * another engine nests recognizers. Until sherpa-onnx owns this slot, every request
 * reports an error rather than pretending to listen.
 */
class AssistRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_SERVER)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
