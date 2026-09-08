package com.thatsimpletech.assist.a11y

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.view.WindowManager
import com.thatsimpletech.assist.core.loop.Observer
import com.thatsimpletech.assist.core.observe.Fingerprint
import com.thatsimpletech.assist.core.observe.NodeFilter
import com.thatsimpletech.assist.core.observe.Rect
import com.thatsimpletech.assist.core.observe.Screen
import com.thatsimpletech.assist.core.observe.WindowKind

/**
 * The core [Observer] backed by the accessibility tree. Secure screens show up as a window
 * with no readable root: we report that and stop (P8), we never try to get around it.
 */
class TreeObserver(
    private val context: Context,
    private val walker: TreeWalker,
    private val activityName: () -> String,
) : Observer {

    override fun observe(): Screen {
        val walk = walker.walk()
        val app = walk.activePackage
        val appWindows = walk.windows.filter { it.kind == WindowKind.APP }
        val readable = walk.nodes.any { n -> appWindows.any { it.id == n.window } }
        val secure = appWindows.isNotEmpty() && !readable
        // IME and overlay windows are not the app: Gboard composing text would leak a password
        // that the password-field rule never sees, because suggestion chips are not isPassword.
        val visibleKinds = setOf(WindowKind.APP, WindowKind.SYSTEM, WindowKind.OTHER)
        val visibleIds = walk.windows.filter { it.kind in visibleKinds }.map { it.id }.toSet()
        val activity = activityName()
        val onQs = if (context is AccessibilityService) {
            QsObserver.detect(context, app, activity)
        } else {
            false
        }
        return Screen(
            app = app,
            activity = activity,
            display = display(),
            nodes = walk.nodes.filter { it.window in visibleIds },
            windows = walk.windows, // keep IME so ObservationBuilder can set kbd=yes
            keyguard = keyguard(),
            secure = secure,
            onQs = onQs,
        )
    }

    override fun fingerprint(): String {
        val walk = walker.walk()
        val screen = Screen(walk.activePackage, activityName(), display(), walk.nodes, walk.windows)
        return Fingerprint.of(screen.app, screen.activity, NodeFilter.select(screen).map { it.identity })
    }

    private fun keyguard(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    private fun display(): Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = wm.currentWindowMetrics.bounds
        return Rect(b.left, b.top, b.right, b.bottom)
    }
}
