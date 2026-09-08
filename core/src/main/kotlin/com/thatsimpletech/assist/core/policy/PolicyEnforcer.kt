package com.thatsimpletech.assist.core.policy

import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.observe.UiNode

/** What the enforcer knows about the task when it judges one action. */
data class TaskContext(
    /** Packages the task started with; the intent lock (plan §4). */
    val goalApps: Set<String>,
    /** Apps the person re-confirmed during the task; they join the goal set. */
    val confirmedApps: Set<String> = emptySet(),
    /** The one-time Tier 1 grant covering the plan has been given. */
    val taskGranted: Boolean = false,
    /** Tier 2 actions already run this turn. */
    val tier2ThisTurn: Int = 0,
)

/** The facts one action is judged on. Built by [PolicyEnforcer.facts]; also what cases.yaml describes. */
data class ActionFacts(
    val verb: String,
    val app: String,
    val acts: Boolean,
    val appScoped: Boolean,
    val keyguard: Boolean,
    val secure: Boolean,
    val targetPassword: Boolean,
    val targetSensitive: Boolean,
    val appAllowlisted: Boolean,
    val openAllowlisted: Boolean,
    val appInGoal: Boolean,
    val appSettings: Boolean,
    val onQs: Boolean,
)

enum class Gate {
    /** Run it. */
    PROCEED,
    /** Tier 1 and the task has no grant yet: show the one card that covers the plan. */
    NEED_TASK_GRANT,
    /** Tier 2: show a card for this action. */
    NEED_CARD,
    /** Tier 2 but this turn already spent its one sensitive action: end the turn first. */
    TURN_LIMIT,
    /** Never, regardless of any approval. */
    REFUSE,
}

data class Decision(
    val tier: Tier,
    val rule: String,
    val reason: String,
    val gate: Gate,
    val facts: ActionFacts,
)

/**
 * The Kotlin enforcer of the policy pack. Pure: no clock, no I/O, no Android. Every branch
 * here has a case in policy/cases.yaml.
 */
class PolicyEnforcer(val pack: PolicyPack) {

    fun decide(
        action: Action,
        screenApp: String,
        target: UiNode?,
        keyguard: Boolean,
        secure: Boolean,
        task: TaskContext,
        onQs: Boolean = false,
    ): Decision {
        val f = facts(action, screenApp, target, keyguard, secure, task, onQs)
        val (tier, rule, reason) = classify(f)
        return Decision(tier, rule, reason, gate(tier, task), f)
    }

    /** Rule walk only, for the conformance suite and for other enforcers to compare against. */
    fun classify(f: ActionFacts): Triple<Tier, String, String> {
        for (r in pack.rules) {
            if (matches(r.`when`, f)) return Triple(r.tier, r.id, r.reason)
        }
        return Triple(pack.default, "default", "no rule matched")
    }

    fun gate(tier: Tier, task: TaskContext): Gate = when (tier) {
        Tier.SILENT -> Gate.PROCEED
        Tier.ONCE_PER_TASK -> if (task.taskGranted) Gate.PROCEED else Gate.NEED_TASK_GRANT
        Tier.EVERY_TIME -> if (task.tier2ThisTurn >= pack.maxTier2PerTurn) Gate.TURN_LIMIT else Gate.NEED_CARD
        Tier.REFUSED -> Gate.REFUSE
    }

    fun facts(
        action: Action,
        screenApp: String,
        target: UiNode?,
        keyguard: Boolean,
        secure: Boolean,
        task: TaskContext,
        onQs: Boolean = false,
    ): ActionFacts {
        val verb = verbKey(action)
        val openTarget = (action as? Action.Open)?.let { pack.resolveOpen(it.app) }
        // The app an action lands in: the launched app for `open`, the screen's app otherwise.
        val app = openTarget?.pkg ?: screenApp
        val goal = task.goalApps + task.confirmedApps
        val appEntry = pack.app(app)
        return ActionFacts(
            verb = verb,
            app = app,
            acts = verb !in VerbKey.PASSIVE,
            appScoped = verb in VerbKey.APP_SCOPED,
            keyguard = keyguard,
            secure = secure,
            targetPassword = target?.password == true,
            targetSensitive = target != null && isSensitive(target, appEntry),
            appAllowlisted = appEntry != null,
            openAllowlisted = action !is Action.Open || openTarget != null,
            appInGoal = app in goal,
            appSettings = app in pack.settingsPackages,
            onQs = onQs,
        )
    }

    fun isSensitive(node: UiNode, app: AppEntry?): Boolean {
        val id = node.resourceId?.substringAfter('/')?.lowercase()
        if (id != null && app != null && app.sensitiveIds.any { id.contains(it.lowercase()) }) return true
        return pack.sensitiveLabels.any { TextMatch.containsWord(node.label, it) }
    }

    private fun matches(w: When, f: ActionFacts): Boolean {
        if (w.verb != null && f.verb !in w.verb) return false
        if (w.acts != null && w.acts != f.acts) return false
        if (w.appScoped != null && w.appScoped != f.appScoped) return false
        if (w.keyguard != null && w.keyguard != f.keyguard) return false
        if (w.secure != null && w.secure != f.secure) return false
        if (w.targetPassword != null && w.targetPassword != f.targetPassword) return false
        if (w.targetSensitive != null && w.targetSensitive != f.targetSensitive) return false
        if (w.appAllowlisted != null && w.appAllowlisted != f.appAllowlisted) return false
        if (w.openAllowlisted != null && w.openAllowlisted != f.openAllowlisted) return false
        if (w.appInGoal != null && w.appInGoal != f.appInGoal) return false
        if (w.appSettings != null && w.appSettings != f.appSettings) return false
        if (w.onQs != null && w.onQs != f.onQs) return false
        return true
    }

    companion object {
        fun verbKey(a: Action): String = when (a) {
            is Action.Tap -> VerbKey.TAP
            is Action.Long -> VerbKey.LONG
            is Action.Type -> VerbKey.TYPE
            is Action.Clear -> VerbKey.CLEAR
            is Action.Scroll -> VerbKey.SCROLL
            is Action.Swipe -> VerbKey.SWIPE
            is Action.Drag -> VerbKey.DRAG
            Action.Back -> VerbKey.BACK
            Action.Home -> VerbKey.HOME
            Action.Recents -> VerbKey.RECENTS
            is Action.Open -> VerbKey.OPEN
            Action.NotifList -> VerbKey.NOTIF_LIST
            is Action.NotifReply -> VerbKey.NOTIF_REPLY
            is Action.NotifOpen -> VerbKey.NOTIF_OPEN
            is Action.ScreenAsk -> VerbKey.SCREEN_ASK
            is Action.Wait -> VerbKey.WAIT
            is Action.Done -> VerbKey.DONE
            is Action.Ask -> VerbKey.ASK
            Action.More -> VerbKey.MORE
            is Action.Call -> VerbKey.CALL
            is Action.Text -> VerbKey.TEXT
            is Action.Alarm -> VerbKey.ALARM
            is Action.Timer -> VerbKey.TIMER
            is Action.Event -> VerbKey.EVENT
            is Action.ContactLookup -> VerbKey.CONTACT_LOOKUP
            is Action.ContactAdd -> VerbKey.CONTACT_ADD
            is Action.Navigate -> VerbKey.NAVIGATE
            is Action.Media -> VerbKey.MEDIA
            is Action.Torch -> VerbKey.TORCH
            is Action.Dnd -> VerbKey.DND
            is Action.Brightness -> VerbKey.BRIGHTNESS
            is Action.Volume -> VerbKey.VOLUME
            Action.Qs -> VerbKey.QS
            is Action.WhatsApp -> VerbKey.WHATSAPP
            is Action.Spotify -> VerbKey.SPOTIFY
            is Action.Gmail -> VerbKey.GMAIL
        }
    }
}
