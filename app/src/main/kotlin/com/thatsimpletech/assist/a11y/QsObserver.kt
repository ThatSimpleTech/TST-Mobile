package com.thatsimpletech.assist.a11y

import android.accessibilityservice.AccessibilityService
import com.thatsimpletech.assist.core.observe.QsShade

/**
 * Shade detection for the `on_qs` policy fact (Workstream H). Best-effort on
 * Pixel 7 Pro first: SystemUI plus a window title/class that looks like QS.
 * Prefer false negatives.
 */
object QsObserver {
    fun detect(service: AccessibilityService, activePackage: String, activity: String): Boolean {
        val windows = service.windows ?: emptyList()
        val labels = ArrayList<String>(windows.size * 2)
        for (w in windows) {
            labels += w.title?.toString().orEmpty()
            labels += w.root?.className?.toString().orEmpty()
        }
        return QsShade.detected(activePackage, activity, labels)
    }
}
