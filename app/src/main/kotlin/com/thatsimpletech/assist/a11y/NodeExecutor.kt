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
        // Non-tree verbs parse and policy-gate; their executors are not built yet.
        is Action.Call, is Action.Text, is Action.Alarm, is Action.Timer, is Action.Event,
        is Action.ContactLookup, is Action.ContactAdd, is Action.Navigate,
        is Action.Torch, is Action.Dnd, is Action.Brightness, is Action.Volume,
        is Action.Media, is Action.WhatsApp, is Action.Spotify, is Action.Gmail,
        Action.Qs -> ExecResult.error("${action.verb.word} is not built yet")
    }

    private inline fun onNode(target: UiNode?, block: (AccessibilityNodeInfo) -> ExecResult): ExecResult {
        if (target == null) return ExecResult.error("no target")
        val live = walker.find(target.identity) ?: return ExecResult.error("control is no longer on screen")
        if (!live.isVisibleToUser) return ExecResult.error("control is not visible")
        return block(live)
    }

    private fun setText(n: AccessibilityNodeInfo, text: String): ExecResult {
        if (n.isPassword) return ExecResult.error("password field")
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return ExecResult.OK
        // Clipboard paste fallback: put the text on the clipboard, then ACTION_PASTE.
        val cm = service.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("tst-assist", text))
        return if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            // TM-012: do not leave the model's text sitting on the clipboard.
            runCatching { cm?.clearPrimaryClip() }
            ExecResult.OK
        } else ExecResult.error("field refused text")
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

    private suspend fun drag(from: Rect, to: Rect): ExecResult {
        val path = Path().apply {
            moveTo(from.centerX.toFloat(), from.centerY.toFloat())
            lineTo(to.centerX.toFloat(), to.centerY.toFloat())
        }
        return gesture(path, 0, 900, willContinue = false, holdFirst = true)
    }

    private suspend fun gesture(path: Path, start: Long, duration: Long, willContinue: Boolean = false, holdFirst: Boolean = false): ExecResult {
        val builder = GestureDescription.Builder()
        if (holdFirst) {
            // Long-press at the path start so drag handles pick it up (TM-011).
            val pos = FloatArray(2)
            android.graphics.PathMeasure(path, false).getPosTan(0f, pos, null)
            val hold = Path().apply { moveTo(pos[0], pos[1]) }
            builder.addStroke(GestureDescription.StrokeDescription(hold, 0, 400, true))
            builder.addStroke(GestureDescription.StrokeDescription(path, 400, duration, willContinue))
        } else {
            builder.addStroke(GestureDescription.StrokeDescription(path, start, duration, willContinue))
        }
        return suspendCancellableCoroutine { cont ->
            val ok = service.dispatchGesture(
                builder.build(),
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
