package com.thatsimpletech.assist.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.grammar.Direction
import com.thatsimpletech.assist.core.grammar.SwipeTarget
import com.thatsimpletech.assist.core.loop.ExecResult
import com.thatsimpletech.assist.core.loop.Executor
import com.thatsimpletech.assist.core.observe.Observation
import com.thatsimpletech.assist.core.observe.Rect
import com.thatsimpletech.assist.core.observe.UiNode
import com.thatsimpletech.assist.core.policy.PolicyPack
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Executes approved actions (plan §7). Accessibility actions first, gesture fallbacks second,
 * exactly in the order the plan lists. The node is re-found by identity on the live tree at
 * execution time; if it is gone, the action fails instead of tapping whatever moved there.
 */
class NodeExecutor(
    private val service: AccessibilityService,
    private val walker: TreeWalker,
    private val pack: PolicyPack,
    private val notifications: NotificationActions,
    private val vision: ScreenAsk? = null,
) : Executor {

    /** Notification verbs are served by the listener service; see notif/AssistNotificationListener. */
    interface NotificationActions {
        fun list(): List<String>
        fun reply(id: String, text: String): ExecResult
        fun open(id: String): ExecResult
    }

    /** `screen ask` goes to a vision brain (M6). Absent until then: the executor says so honestly. */
    interface ScreenAsk {
        suspend fun ask(question: String): ExecResult
    }

    override suspend fun execute(action: Action, target: UiNode?, observation: Observation): ExecResult = when (action) {
        is Action.Tap -> onNode(target) { n -> if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ExecResult.OK else tapGesture(n.center()) }
        is Action.Long -> onNode(target) { n -> if (n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) ExecResult.OK else holdGesture(n.center()) }
        is Action.Type -> onNode(target) { n -> setText(n, action.text) }
        is Action.Clear -> onNode(target) { n -> setText(n, "") }
        is Action.Scroll -> onNode(target) { n -> scroll(n, action.direction, target!!.bounds) }
        is Action.Swipe -> when (val t = action.target) {
            is SwipeTarget.Hint -> swipe(target?.bounds ?: return ExecResult.error("target gone"), action.direction)
            SwipeTarget.Screen -> swipe(observationDisplay(observation), action.direction)
        }
        is Action.Drag -> {
            val from = target?.bounds ?: return ExecResult.error("drag source gone")
            val to = observation.node(action.to)?.bounds ?: return ExecResult.error("drag destination gone")
            drag(from, to)
        }
        Action.Back -> global(AccessibilityService.GLOBAL_ACTION_BACK)
        Action.Home -> global(AccessibilityService.GLOBAL_ACTION_HOME)
        Action.Recents -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
        is Action.Open -> open(action.app)
        Action.NotifList -> ExecResult(true, notifications.list().joinToString("\n"))
        is Action.NotifReply -> notifications.reply(action.id, action.text)
        is Action.NotifOpen -> notifications.open(action.id)
        is Action.ScreenAsk -> vision?.ask(action.question) ?: ExecResult.error("no vision model is configured; screen ask is unavailable")
        is Action.Wait -> { delay(action.seconds * 1000L); ExecResult.OK }
        // Terminal and paging verbs never reach the executor; the loop handles them.
        is Action.Done, is Action.Ask, Action.More -> ExecResult.error("not an executable verb")
    }

    private inline fun onNode(target: UiNode?, block: (AccessibilityNodeInfo) -> ExecResult): ExecResult {
        if (target == null) return ExecResult.error("no target")
        val live = walker.find(target.identity) ?: return ExecResult.error("control is no longer on screen")
        if (!live.isVisibleToUser) return ExecResult.error("control is not visible")
        // C3, second line of defence: the identity matched, now the words must too. A recycled
        // list row keeps its identity when its contents change; what was approved is the label.
        if (!target.password && target.label.isNotBlank() && TreeWalker.effectiveLabel(live) != target.label) {
            return ExecResult.error("control changed, look again")
        }
        return block(live)
    }

    private fun setText(n: AccessibilityNodeInfo, text: String): ExecResult {
        if (n.isPassword) return ExecResult.error("password field")
        if (!n.isEditable) return ExecResult.error("not a text field")
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return ExecResult.OK
        // Clipboard paste fallback. The clip is marked sensitive (no preview, no history) and
        // cleared again right after, so typed text never lingers where other apps can read it.
        val cm = service.getSystemService(android.content.ClipboardManager::class.java) ?: return ExecResult.error("field refused text")
        val clip = android.content.ClipData.newPlainText("tst-assist", text).apply {
            description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        return try {
            cm.setPrimaryClip(clip)
            if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) ExecResult.OK else ExecResult.error("field refused text")
        } finally {
            runCatching { cm.clearPrimaryClip() }
        }
    }

    private suspend fun scroll(n: AccessibilityNodeInfo, dir: Direction, bounds: Rect): ExecResult {
        val action = when (dir) {
            Direction.UP -> AccessibilityAction.ACTION_SCROLL_UP
            Direction.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
            Direction.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
            Direction.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (n.performAction(action.id)) return ExecResult.OK
        val legacy = if (dir == Direction.DOWN || dir == Direction.RIGHT) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (n.performAction(legacy)) return ExecResult.OK
        return swipe(bounds, dir.opposite())
    }

    private fun Direction.opposite() = when (this) {
        Direction.UP -> Direction.DOWN
        Direction.DOWN -> Direction.UP
        Direction.LEFT -> Direction.RIGHT
        Direction.RIGHT -> Direction.LEFT
    }

    private fun global(action: Int): ExecResult =
        if (service.performGlobalAction(action)) ExecResult.OK else ExecResult.error("system refused")

    /** Launch by label through the allowlist only. The label is a lookup key, never a command. */
    private fun open(label: String): ExecResult {
        val app = pack.resolveOpen(label) ?: return ExecResult.error("'$label' is not an allowlisted app")
        val intent = service.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return ExecResult.error("${app.label} is not installed")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            service.startActivity(intent)
            ExecResult.OK
        } catch (e: Exception) {
            ExecResult.error("could not open ${app.label}")
        }
    }

    // ---- gestures ----

    private fun AccessibilityNodeInfo.center(): Pair<Float, Float> {
        val r = android.graphics.Rect().also { getBoundsInScreen(it) }
        return r.exactCenterX() to r.exactCenterY()
    }

    private fun observationDisplay(obs: Observation): Rect {
        val wm = service.getSystemService(android.view.WindowManager::class.java)
        val b = wm.currentWindowMetrics.bounds
        return Rect(b.left, b.top, b.right, b.bottom)
    }

    private suspend fun tapGesture(at: Pair<Float, Float>): ExecResult =
        gesture(Path().apply { moveTo(at.first, at.second) }, 0, 60)

    private suspend fun holdGesture(at: Pair<Float, Float>): ExecResult =
        gesture(Path().apply { moveTo(at.first, at.second) }, 0, 700)

    private suspend fun swipe(r: Rect, dir: Direction): ExecResult {
        val cx = r.centerX.toFloat()
        val cy = r.centerY.toFloat()
        val dx = r.width * 0.35f
        val dy = r.height * 0.35f
        val (x0, y0, x1, y1) = when (dir) {
            Direction.UP -> listOf(cx, cy + dy, cx, cy - dy)
            Direction.DOWN -> listOf(cx, cy - dy, cx, cy + dy)
            Direction.LEFT -> listOf(cx + dx, cy, cx - dx, cy)
            Direction.RIGHT -> listOf(cx - dx, cy, cx + dx, cy)
        }
        return gesture(Path().apply { moveTo(x0, y0); lineTo(x1, y1) }, 0, 300)
    }

    /**
     * One finger: press and hold on the source so drag handles engage, then, as a
     * continuation of the same stroke, move to the destination and lift.
     */
    private suspend fun drag(from: Rect, to: Rect): ExecResult {
        val hold = GestureDescription.StrokeDescription(
            Path().apply { moveTo(from.centerX.toFloat(), from.centerY.toFloat()) }, 0, 400, true,
        )
        val held = dispatch(hold)
        if (!held.ok) return held
        val move = hold.continueStroke(
            Path().apply {
                moveTo(from.centerX.toFloat(), from.centerY.toFloat())
                lineTo(to.centerX.toFloat(), to.centerY.toFloat())
            },
            0, 700, false,
        )
        return dispatch(move)
    }

    private suspend fun gesture(path: Path, start: Long, duration: Long, willContinue: Boolean = false): ExecResult =
        dispatch(GestureDescription.StrokeDescription(path, start, duration, willContinue))

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): ExecResult {
        val description = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val ok = service.dispatchGesture(
                description,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(ExecResult.OK) }
                    override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(ExecResult.error("gesture cancelled")) }
                },
                null,
            )
            if (!ok && cont.isActive) cont.resume(ExecResult.error("gesture refused"))
        }
    }
}
