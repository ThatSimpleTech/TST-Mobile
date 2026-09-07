package com.thatsimpletech.assist.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * The observer/executor host. It keeps the last activity name (from window-state events)
 * and hands itself to the app graph; the task runner lives in the foreground service.
 */
class AssistAccessibilityService : AccessibilityService() {
    @Volatile
    var lastActivity: String = ""
        private set

    lateinit var walker: TreeWalker
        private set

    override fun onServiceConnected() {
        walker = TreeWalker(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString()
            if (!cls.isNullOrEmpty()) lastActivity = cls.substringAfterLast('.')
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** Null when the accessibility service is off: the app then runs in no-accessibility mode (P6). */
        @Volatile
        var instance: AssistAccessibilityService? = null
            private set
    }
}
