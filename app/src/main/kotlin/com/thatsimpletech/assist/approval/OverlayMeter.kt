package com.thatsimpletech.assist.approval

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Live spend chip at the top of the screen (plan §5). Not the gold tap highlight
 * ([OverlayCard]) and not the Approve/Deny card. Untouchable; notification copy is
 * the same string via [com.thatsimpletech.assist.core.meter.MeterText].
 */
class OverlayMeter(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var chip: TextView? = null

    fun show(text: String) {
        main.post {
            hideNow()
            val dp = service.resources.displayMetrics.density
            val tv = TextView(service).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(220, 24, 24, 28))
                gravity = Gravity.CENTER
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            }
            wm.addView(tv, params())
            chip = tv
        }
    }

    fun update(text: String) {
        main.post { chip?.text = text }
    }

    fun hide() {
        main.post { hideNow() }
    }

    private fun hideNow() {
        chip?.let { runCatching { wm.removeView(it) } }
        chip = null
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP }
}
