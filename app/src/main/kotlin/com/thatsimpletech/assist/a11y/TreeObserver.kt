package com.thatsimpletech.assist.a11y

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
 * The core [Observer] backed by the accessibility tree. One rule builds the screen for both
 * observe() and fingerprint(), so the stale check compares like with like: our own overlay
 * windows (the approval card, the highlight) are never part of either.
 *
 * FLAG_SECURE is not visible to an accessibility service (it only blocks screenshots), so
 * `secure` is not derived here; a window with no readable tree simply yields no nodes and
 * the coverage detector reports "no readable nodes" (P8: report and hand off).
 */
class TreeObserver(
    private val context: Context,
    private val walker: TreeWalker,
    private val activityName: () -> String,
) : Observer {

    override fun observe(): Screen = screen(walker.walk())

    override fun fingerprint(): String {
        val s = screen(walker.walk())
        return Fingerprint.ofNodes(s.app, s.activity, NodeFilter.select(s))
    }

    private fun screen(walk: TreeWalker.Walk): Screen {
        val windows = walk.windows.filter { it.kind != WindowKind.OVERLAY }
        val ids = windows.mapTo(HashSet()) { it.id }
        return Screen(
            app = walk.activePackage,
            activity = activityName(),
            display = display(),
            nodes = walk.nodes.filter { it.window in ids },
            windows = windows,
            keyguard = keyguard(),
            secure = false,
        )
    }

    private fun keyguard(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    private fun display(): Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = wm.currentWindowMetrics.bounds
        return Rect(b.left, b.top, b.right, b.bottom)
    }
}
