package com.thatsimpletech.assist.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.os.Build
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
import com.thatsimpletech.assist.core.policy.TextMatch
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
        is Action.Tap -> tap(target)
        is Action.Long -> onNode(target) { n -> if (n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) ExecResult.OK else holdGesture(n.center()) }
        is Action.Type -> type(target, action.text)
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

    /** Compact controls (icons, Send) are tapped at their pixel center; slabs use ACTION_CLICK. */
    private suspend fun tap(target: UiNode?): ExecResult {
        if (target == null) return ExecResult.error("no target")
        val live = walker.find(target.identity) ?: return ExecResult.error("control is no longer on screen")
        if (!live.isVisibleToUser) return ExecResult.error("control is not visible")
        val compact = target.bounds.area in 1 until 80_000
        if (compact) {
            val g = tapGesture(live.center())
            if (g.ok) return g
        }
        if (live.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ExecResult.OK
        return tapGesture(live.center())
    }

    private inline fun onNode(target: UiNode?, block: (AccessibilityNodeInfo) -> ExecResult): ExecResult {
        if (target == null) return ExecResult.error("no target")
        val live = walker.find(target.identity) ?: return ExecResult.error("control is no longer on screen")
        if (!live.isVisibleToUser) return ExecResult.error("control is not visible")
        return block(live)
    }

    private suspend fun type(target: UiNode?, text: String): ExecResult {
        if (text.isEmpty()) return ExecResult.error("nothing to type")
        val start = target?.let { walker.find(it.identity) }
        val fields = editableFields(start)
        if (fields.isEmpty()) return ExecResult.error("no editable field on screen")

        val cm = service.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("ezer", text))

        for (field in fields) {
            if (field.isPassword) continue
            focusField(field)
            delay(300)
            // WhatsApp advertises SET_TEXT and no-ops. Believe the node text, not the boolean.
            trySetText(field, text)
            if (holdsText(field, text)) {
                runCatching { cm?.clearPrimaryClip() }
                delay(400) // WhatsApp swaps the mic for Send after the box has text.
                return ExecResult.OK
            }
            field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            delay(200)
            if (holdsText(field, text)) {
                runCatching { cm?.clearPrimaryClip() }
                delay(400)
                return ExecResult.OK
            }
        }
        return ExecResult.error("field refused text")
    }

    private suspend fun focusField(n: AccessibilityNodeInfo) {
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!n.isFocused) tapGesture(n.center())
    }

    private fun trySetText(n: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun holdsText(n: AccessibilityNodeInfo, want: String): Boolean {
        n.refresh()
        val got = gatherText(n)
        val needle = want.take(24)
        return got.contains(needle)
    }

    private fun gatherText(n: AccessibilityNodeInfo): String = buildString {
        fun walk(x: AccessibilityNodeInfo) {
            x.text?.toString()?.let { append(it) }
            for (i in 0 until x.childCount) x.getChild(i)?.let { walk(it) }
        }
        walk(n)
    }

    /** Target and its children first, then every editable field, compose-box ids and bottom-of-screen first. */
    private fun editableFields(start: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val found = ArrayList<AccessibilityNodeInfo>()
        val seen = HashSet<Int>()
        fun collect(n: AccessibilityNodeInfo?) {
            if (n == null) return
            fun walk(x: AccessibilityNodeInfo) {
                if (!seen.add(System.identityHashCode(x))) return
                if (x.isEditable && x.isVisibleToUser && x.isEnabled && !x.isPassword) found += x
                for (i in 0 until x.childCount) walk(x.getChild(i) ?: continue)
            }
            walk(n)
        }
        collect(start)
        for (w in service.windows.orEmpty()) collect(w.root)
        return found.distinctBy { it.viewIdResourceName ?: it.className?.toString() ?: it.toString() }
            .sortedByDescending { scoreField(it) }
    }

    private fun scoreField(n: AccessibilityNodeInfo): Int {
        val id = n.viewIdResourceName.orEmpty().lowercase()
        val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
        var s = r.top
        if (n.isFocused) s += 10_000
        if ("entry" in id || "compose" in id || "input" in id || "message" in id) s += 8_000
        return s
    }

    private fun setText(n: AccessibilityNodeInfo, text: String): ExecResult {
        if (n.isPassword) return ExecResult.error("password field")
        trySetText(n, text)
        if (holdsText(n, text) || text.isEmpty()) return ExecResult.OK
        val cm = service.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("ezer", text))
        val pasted = n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted && (text.isEmpty() || holdsText(n, text))) {
            runCatching { cm?.clearPrimaryClip() }
            return ExecResult.OK
        }
        return ExecResult.error("field refused text")
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

    /** Launch by launcher label. Allowlisted names first, then any installed launcher app. */
    private suspend fun open(label: String): ExecResult {
        val resolved = resolveLauncher(label)
            ?: return ExecResult.error("no installed app named '$label'")
        val (pkg, intent) = resolved
        val current = walker.walk().activePackage
        if (sameApp(current, pkg)) return ExecResult(true, "already in $label")
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        if (!launch(intent)) return ExecResult.error("could not open $label")
        delay(1_400)
        val now = walker.walk().activePackage
        return if (sameApp(now, pkg)) ExecResult.OK
        else ExecResult.error("opened $label but the screen is still $now")
    }

    private fun resolveLauncher(label: String): Pair<String, Intent>? {
        pack.resolveOpen(label)?.let { entry ->
            val pkg = resolveInstalled(entry.pkg) ?: return@let
            val intent = launchIntent(pkg) ?: return@let
            return pkg to intent
        }
        val key = TextMatch.fold(label)
        if (key.isEmpty()) return null
        val pm = service.packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hits = pm.queryIntentActivities(probe, 0)
        val exact = hits.firstOrNull { TextMatch.fold(it.loadLabel(pm).toString()) == key }
        val word = exact ?: hits.firstOrNull { TextMatch.containsWord(it.loadLabel(pm).toString(), label) }
        val hit = word ?: return null
        val pkg = hit.activityInfo.packageName
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(pkg, hit.activityInfo.name)
        return pkg to intent
    }

    private fun sameApp(current: String, pkg: String): Boolean {
        if (current == pkg) return true
        val wa = setOf("com.whatsapp", "com.whatsapp.w4b")
        return current in wa && pkg in wa
    }

    private fun resolveInstalled(pkg: String): String? {
        if (launchIntent(pkg) != null) return pkg
        if (pkg == "com.whatsapp" && launchIntent("com.whatsapp.w4b") != null) return "com.whatsapp.w4b"
        return null
    }

    private fun launchIntent(pkg: String): Intent? {
        val pm = service.packageManager
        pm.getLaunchIntentForPackage(pkg)?.let { return it }
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val hit = pm.queryIntentActivities(probe, 0).firstOrNull() ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(hit.activityInfo.packageName, hit.activityInfo.name)
    }

    private fun launch(intent: Intent): Boolean {
        val opts = launchOptions()
        val tries = listOf(
            { service.startActivity(intent, opts) },
            { service.applicationContext.startActivity(intent, opts) },
            {
                val pi = PendingIntent.getActivity(
                    service,
                    pkgCode(intent),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                pi.send(service, 0, null, null, null, null, opts)
            },
        )
        return tries.any { runCatching { it() }.isSuccess }
    }

    private fun pkgCode(intent: Intent): Int = (intent.`package` ?: intent.component?.packageName ?: "app").hashCode()

    private fun launchOptions(): android.os.Bundle {
        val opts = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 35) {
            opts.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
            )
        } else if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            opts.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        }
        return opts.toBundle()
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
