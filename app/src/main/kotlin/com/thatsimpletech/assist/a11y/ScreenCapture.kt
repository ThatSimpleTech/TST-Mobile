package com.thatsimpletech.assist.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One JPEG of the current display (M6). Scaled so a vision call stays small. Secure
 * windows fail honestly; the pixels never go to disk.
 */
object ScreenCapture {
    const val MAX_EDGE = 1080
    const val JPEG_QUALITY = 70

    suspend fun jpeg(service: AccessibilityService): ByteArray = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val bytes = encode(screenshot)
                        if (cont.isActive) cont.resume(bytes)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(failMessage(errorCode)))
                }
            },
        )
    }

    internal fun encode(screenshot: AccessibilityService.ScreenshotResult): ByteArray {
        val hw = screenshot.hardwareBuffer
        try {
            val wrapped = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                ?: throw IllegalStateException("screenshot wrap failed")
            try {
                val software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("screenshot copy failed")
                try {
                    return jpegBytes(software)
                } finally {
                    software.recycle()
                }
            } finally {
                wrapped.recycle()
            }
        } finally {
            hw.close()
        }
    }

    internal fun jpegBytes(src: Bitmap): ByteArray {
        val scaled = scale(src)
        try {
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw IllegalStateException("jpeg compress failed")
            }
            return out.toByteArray()
        } finally {
            if (scaled !== src) scaled.recycle()
        }
    }

    private fun scale(src: Bitmap): Bitmap {
        val edge = maxOf(src.width, src.height)
        if (edge <= MAX_EDGE) return src
        val f = MAX_EDGE.toFloat() / edge
        val w = (src.width * f).toInt().coerceAtLeast(1)
        val h = (src.height * f).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    fun failMessage(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "this screen blocks screenshots"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "screen driver cannot take a screenshot"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "screenshot requested too soon; wait a moment"
        else -> "screenshot failed ($code)"
    }
}
