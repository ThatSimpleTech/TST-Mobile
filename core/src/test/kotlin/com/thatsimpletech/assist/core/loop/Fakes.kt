package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.grammar.ParseResult
import com.thatsimpletech.assist.core.observe.Fingerprint
import com.thatsimpletech.assist.core.observe.NodeFilter
import com.thatsimpletech.assist.core.observe.Observation
import com.thatsimpletech.assist.core.observe.Rect
import com.thatsimpletech.assist.core.observe.Role
import com.thatsimpletech.assist.core.observe.Screen
import com.thatsimpletech.assist.core.observe.UiNode
import com.thatsimpletech.assist.core.policy.Decision

/** Test doubles for the loop ports. Everything is scripted or recorded; nothing is clever. */

/** Replies in order; a script that runs dry is a test bug, not a model answer. */
class ScriptedPlanner(
    private val replies: List<String>,
    private val onCall: (Int) -> Unit = {},
    private val goalApps: Set<String> = emptySet(),
) : Planner {
    val prompts = ArrayList<String>()
    var planGoalAppsCalls = 0
        private set

    override suspend fun next(prompt: String): String {
        prompts += prompt
        val i = prompts.size - 1
        onCall(i)
        return replies.getOrNull(i) ?: error("planner script exhausted at call ${i + 1}")
    }

    override suspend fun planGoalApps(goal: String): Set<String> {
        planGoalAppsCalls++
        return goalApps
    }
}

/**
 * Returns [screens] in order, then repeats the last one. [fingerprint] matches the screen
 * the way the app's observer will (same inputs as the builder) unless a scripted override
 * is queued, which is how a test makes the screen "change" under the model.
 */
class ScriptedObserver(private val screens: List<Screen>) : Observer {
    val fingerprintOverrides = ArrayDeque<String>()
    var observeCalls = 0
        private set
    var fingerprintCalls = 0
        private set
    private var current: Screen? = null

    override fun observe(): Screen {
        val s = screens[minOf(observeCalls, screens.size - 1)]
        observeCalls++
        current = s
        return s
    }

    override fun fingerprint(): String {
        fingerprintCalls++
        fingerprintOverrides.removeFirstOrNull()?.let { return it }
        val s = current ?: error("fingerprint() before observe()")
        return Fingerprint.of(s.app, s.activity, NodeFilter.select(s).map { it.identity })
    }
}

class RecordingExecutor(private val result: ExecResult = ExecResult.OK) : Executor {
    data class Call(val action: Action, val target: UiNode?, val observation: Observation)

    val calls = ArrayList<Call>()
    val actions: List<Action> get() = calls.map { it.action }

    override suspend fun execute(action: Action, target: UiNode?, observation: Observation): ExecResult {
        calls += Call(action, target, observation)
        return result
    }
}

class ScriptedApprovals(private val taskGrant: Boolean = true, cards: List<Boolean> = emptyList()) : ApprovalSurface {
    data class GrantRequest(val goal: String, val apps: Set<String>, val verbs: Set<String>)
    data class CardRequest(val action: Action, val plainWords: String, val target: UiNode?, val reason: String)

    private val cardAnswers = ArrayDeque(cards)
    val grants = ArrayList<GrantRequest>()
    val cards = ArrayList<CardRequest>()

    override suspend fun requestTaskGrant(goal: String, apps: Set<String>, verbs: Set<String>): Boolean {
        grants += GrantRequest(goal, apps, verbs)
        return taskGrant
    }

    override suspend fun requestCard(action: Action, plainWords: String, target: UiNode?, reason: String): Boolean {
        cards += CardRequest(action, plainWords, target, reason)
        return cardAnswers.removeFirstOrNull() ?: error("no scripted card answer for ${action.render()}")
    }
}

class FakeKill(override var killed: Boolean = false) : KillSwitch

/** Scripted [EndStateValidator]; [calls] is how many times `done` reached it. */
class RecordingValidator(private val result: Validation = Validation.Pass) : EndStateValidator {
    var calls = 0
        private set

    override suspend fun validate(goal: String, last: Observation, goalApps: Set<String>): Validation {
        calls++
        return result
    }
}

class RecordingListener : RunListener {
    data class Step(val step: Int, val observation: Observation, val reply: String, val parsed: ParseResult, val decision: Decision?, val result: ExecResult?)

    val steps = ArrayList<Step>()
    val approvals = ArrayList<Pair<ApprovalKind, Boolean>>()
    val ends = ArrayList<Outcome>()

    override fun onStep(step: Int, observation: Observation, reply: String, parsed: ParseResult, decision: Decision?, result: ExecResult?) {
        steps += Step(step, observation, reply, parsed, decision, result)
    }

    override fun onApproval(kind: ApprovalKind, approved: Boolean) {
        approvals += kind to approved
    }

    override fun onEnd(outcome: Outcome) {
        ends += outcome
    }
}

/** Screens shaped like the plan's WhatsApp example; hints follow reading order (top, then left). */
object Screens {
    val display = Rect(0, 0, 1080, 2400)

    fun node(
        id: String, role: Role, label: String = "", top: Int = 0, height: Int = 100,
        clickable: Boolean = role == Role.BTN, editable: Boolean = role == Role.EDIT,
        scrollable: Boolean = role == Role.LIST, focused: Boolean = false,
        meta: Map<String, String> = emptyMap(),
    ) = UiNode(
        identity = id, role = role, bounds = Rect(0, top, 1080, top + height), label = label,
        clickable = clickable, editable = editable, scrollable = scrollable, focused = focused, meta = meta,
    )

    /** [1] list, [2] text from Maria, [3] edit, [4] btn Send, [5] btn Attach. */
    fun whatsapp(keyguard: Boolean = false) = Screen(
        app = "com.whatsapp", activity = "Conversation", display = display, keyguard = keyguard,
        nodes = listOf(
            node("list", Role.LIST, top = 200, height = 1900),
            node("msg", Role.TEXT, "Hey are we still on for tonight?", top = 1900, clickable = false, meta = mapOf("from" to "Maria")),
            node("edit", Role.EDIT, "Type a message", top = 2200, focused = true),
            node("send", Role.BTN, "Send", top = 2300),
            node("attach", Role.BTN, "Attach", top = 2300),
        ),
    )

    /** [1] list, [2] btn Ok, in Gmail: outside a WhatsApp task's goal apps. */
    fun gmail() = Screen(
        app = "com.google.android.gm", activity = "Inbox", display = display,
        nodes = listOf(
            node("list", Role.LIST, top = 100, height = 2000),
            node("ok", Role.BTN, "Ok", top = 2300),
        ),
    )

    /** 150 buttons: three pages of 60 at the default page size. */
    fun big() = Screen(
        app = "com.whatsapp", activity = "Chats", display = display,
        nodes = (1..150).map { node("n$it", Role.BTN, "Chat $it", top = it * 15) },
    )
}
