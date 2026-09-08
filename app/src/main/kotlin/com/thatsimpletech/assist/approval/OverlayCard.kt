package com.thatsimpletech.assist.approval

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.thatsimpletech.assist.core.observe.Rect

/**
 * The overlay half of the approval surface: a highlight around the exact control about to be
 * touched, and a card in plain words with Approve and Deny (plan §4). Two windows: the highlight
 * is never touchable, the card is. Both are TYPE_ACCESSIBILITY_OVERLAY, which needs no extra
 * permission beyond the accessibility service itself.
 */
class OverlayCard(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var highlight: View? = null
    private var card: View? = null

    fun show(title: String, body: String, target: Rect?, onApprove: () -> Unit, onDeny: () -> Unit) {
        main.post {
            hideNow()
            if (target != null) {
                val h = HighlightView(service, target)
                wm.addView(h, fullScreenParams())
                highlight = h
            }
            val c = buildCard(title, body, onApprove, onDeny)
            wm.addView(c, cardParams())
            card = c
        }
    }

    fun hide() {
        main.post { hideNow() }
    }

    private fun hideNow() {
        highlight?.let { runCatching { wm.removeView(it) } }
        card?.let { runCatching { wm.removeView(it) } }
        highlight = null
        card = null
    }

    private fun buildCard(title: String, body: String, onApprove: () -> Unit, onDeny: () -> Unit): View {
        val ctx: Context = service
        val dp = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 24, 24, 28))
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        root.addView(TextView(ctx).apply { text = title; textSize = 18f; setTextColor(Color.WHITE) })
        root.addView(TextView(ctx).apply { text = body; textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt()) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(Button(ctx).apply { text = "Deny"; setOnClickListener { onDeny() } })
        row.addView(Button(ctx).apply { text = "Approve"; setOnClickListener { onApprove() } })
        root.addView(row)
        return root
    }

    private fun fullScreenParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun cardParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.BOTTOM }

    /** Draws a thick rounded stroke around the target. Nothing else, nothing touchable. */
    private class HighlightView(ctx: Context, private val target: Rect) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f * ctx.resources.displayMetrics.density
            color = Color.rgb(255, 196, 0)
        }

        override fun onDraw(canvas: Canvas) {
            val pad = paint.strokeWidth
            canvas.drawRoundRect(
                target.left - pad, target.top - pad, target.right + pad, target.bottom + pad,
                12f, 12f, paint,
            )
        }
    }
}
