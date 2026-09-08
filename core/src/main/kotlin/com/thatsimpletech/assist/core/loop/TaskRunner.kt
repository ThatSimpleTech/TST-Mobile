package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.grammar.ActionParser
import com.thatsimpletech.assist.core.grammar.ParseResult
import com.thatsimpletech.assist.core.observe.LastResult
import com.thatsimpletech.assist.core.observe.Observation
import com.thatsimpletech.assist.core.observe.ObservationBuilder
import com.thatsimpletech.assist.core.observe.ObservationFormatter
import com.thatsimpletech.assist.core.observe.Trailer
import com.thatsimpletech.assist.core.policy.Decision
import com.thatsimpletech.assist.core.policy.Gate
import com.thatsimpletech.assist.core.policy.PolicyEnforcer
import com.thatsimpletech.assist.core.policy.PolicyPack
import com.thatsimpletech.assist.core.policy.TaskContext
import com.thatsimpletech.assist.core.policy.Tier

/**
 * The task loop (plan §4, §7, M2, C3). One model call per step: observe, prompt, parse,
 * judge, approve, execute. Everything the model says goes through the closed grammar; the
 * only thing that reaches the executor is a parsed action the enforcer let through, checked
 * against a screen that has not changed since the model looked at it.
 *
 * [instructions] is the resolved instruction stack (ASSISTANT.md and friends); it sits above
 * the observation block, never inside it. [budget] and [loopRepeatLimit] default to the
 * enforcer's policy pack so a caller cannot loosen them by accident.
 */
class TaskRunner(
    private val observer: Observer,
    private val planner: Planner,
    private val executor: Executor,
    private val approvals: ApprovalSurface,
    private val kill: KillSwitch,
    private val enforcer: PolicyEnforcer,
    private val builder: ObservationBuilder,
    private val parser: ActionParser,
    private val instructions: String,
    private val listener: RunListener? = null,
    private val budget: StepBudget = StepBudget.of(enforcer.pack),
    private val loopRepeatLimit: Int = enforcer.pack.loopRepeatLimit,
    private val notifications: NotificationDirectory? = null,
) {
    /** The kinds of model failure that end the run when they happen twice in a row. */
    private enum class Failure { PARSE, REFUSED, DENIED }

    suspend fun run(goal: String, goalApps: Set<String>): Outcome {
        val loops = LoopDetector(loopRepeatLimit)
        val tier1Verbs = tier1Verbs(enforcer.pack)

        var last: LastResult? = null
        var notifListing: List<String>? = null
        var parseNote: String? = null
        var tier2Pending: Action? = null
        var lastAction: Action? = null
        var failure: Failure? = null
        var failures = 0

        var taskGranted = false
        val confirmedApps = LinkedHashSet<String>()
        var tier2ThisTurn = 0

        fun end(outcome: Outcome): Outcome {
            listener?.onEnd(outcome)
            return outcome
        }

        /** Returns true when this failure is the second in a row of its kind. */
        fun fail(kind: Failure): Boolean {
            if (failure == kind) failures++ else {
                failure = kind
                failures = 1
            }
            return failures >= 2
        }

        fun clearFailures() {
            failure = null
            failures = 0
        }

        var step = 0
        while (true) {
            step++
            if (kill.killed) return end(Outcome.Stopped(KILLED))
            if (!budget.allows(step)) return end(Outcome.Stopped("step budget of ${budget.limit} used up"))

            // `more` pages the screen the model already saw; anything else looks again.
            val obs: Observation = if (lastAction == Action.More) {
                builder.more() ?: builder.observe(observer.observe())
            } else {
                builder.observe(observer.observe())
            }
            // A new observation opens a new turn: the one Tier 2 per turn is available again.
            tier2ThisTurn = 0

            val prompt = buildString {
                append(instructions).append("\n\n")
                append(ObservationFormatter.format(obs, Trailer(goal, step, budget.limit, last, tier2Pending)))
                // A parse error has no action to put in LAST, so the reason rides the STEP line by itself.
                parseNote?.let { append("   LAST: error ").append(ObservationFormatter.clean(it, 120)) }
                // The listing from `notif list` is its own data block, shown once, one line per notification.
                notifListing?.let { append('\n').append(ObservationFormatter.formatBlock("NOTIF", it)) }
            }
            notifListing = null
            val reply = planner.next(prompt)
            val parsed = parser.parse(reply, obs.hints)

            if (parsed is ParseResult.Error) {
                listener?.onStep(step, obs, reply, parsed, null, null)
                if (fail(Failure.PARSE)) return end(Outcome.Stopped("could not parse the model's reply twice: ${parsed.reason}"))
                parseNote = parsed.reason
                last = null
                tier2Pending = null
                lastAction = null
                continue
            }
            parseNote = null
            tier2Pending = null
            val action = (parsed as ParseResult.Ok).action

            if (loops.record(action.render())) {
                listener?.onStep(step, obs, reply, parsed, null, null)
                return end(Outcome.Stopped("looping: `${action.render()}` repeated ${loops.streak} times"))
            }

            when (action) {
                is Action.Done -> {
                    listener?.onStep(step, obs, reply, parsed, null, null)
                    return end(Outcome.Done(action.summary))
                }
                is Action.Ask -> {
                    listener?.onStep(step, obs, reply, parsed, null, null)
                    return end(Outcome.Ask(action.question))
                }
                Action.More -> {
                    listener?.onStep(step, obs, reply, parsed, null, null)
                    clearFailures()
                    last = LastResult(action, ok = true)
                    lastAction = action
                    continue
                }
                else -> {}
            }

            val target = action.hints.firstOrNull()?.let { obs.node(it) }
            val task = TaskContext(goalApps, confirmedApps.toSet(), taskGranted, tier2ThisTurn)
            // Notification verbs are judged by the posting app; unknown means not allowlisted.
            val appOverride = when (action) {
                is Action.NotifReply -> notifications?.packageOf(action.id) ?: ""
                is Action.NotifOpen -> notifications?.packageOf(action.id) ?: ""
                else -> null
            }
            val decision = enforcer.decide(action, obs.app, target, obs.keyguard, obs.secure, task, appOverride)
            var countsAsTier2 = false

            when (decision.gate) {
                Gate.REFUSE -> {
                    listener?.onStep(step, obs, reply, parsed, decision, null)
                    if (fail(Failure.REFUSED)) return end(Outcome.Stopped("refused twice: ${decision.reason}"))
                    last = LastResult(action, ok = false, detail = "refused: ${decision.reason}")
                    lastAction = action
                    continue
                }
                Gate.NEED_TASK_GRANT -> {
                    val ok = approvals.requestTaskGrant(goal, goalApps, tier1Verbs)
                    listener?.onApproval(ApprovalKind.TASK_GRANT, ok)
                    if (!ok) {
                        listener?.onStep(step, obs, reply, parsed, decision, null)
                        return end(Outcome.Stopped("task not approved"))
                    }
                    taskGranted = true
                }
                Gate.NEED_CARD -> {
                    val ok = approvals.requestCard(action, action.plainWords(), target, decision.reason)
                    listener?.onApproval(ApprovalKind.CARD, ok)
                    if (!ok) {
                        listener?.onStep(step, obs, reply, parsed, decision, null)
                        if (fail(Failure.DENIED)) return end(Outcome.Stopped("denied twice: ${action.plainWords()}"))
                        last = LastResult(action, ok = false, detail = "denied")
                        lastAction = action
                        continue
                    }
                    // The intent lock re-confirm: approving an action in an outside app admits that app.
                    if (decision.rule == OUTSIDE_GOAL_APPS) confirmedApps += decision.facts.app
                    countsAsTier2 = true
                }
                Gate.TURN_LIMIT -> {
                    // Cannot happen while a fresh observation resets the count, but the gate exists.
                    listener?.onStep(step, obs, reply, parsed, decision, null)
                    clearFailures()
                    last = LastResult(action, ok = false, detail = "one sensitive action per turn; look at the screen first")
                    tier2Pending = action
                    lastAction = action
                    continue
                }
                Gate.PROCEED -> {}
            }

            // Approvals take time; the person may have hit the switch meanwhile.
            if (kill.killed) {
                listener?.onStep(step, obs, reply, parsed, decision, null)
                return end(Outcome.Stopped(KILLED))
            }
            // C3: the hint was planned against one screen; execute only against that same screen.
            if (observer.fingerprint() != obs.fingerprint) {
                listener?.onStep(step, obs, reply, parsed, decision, null)
                last = LastResult(action, ok = false, detail = "screen changed, look again")
                lastAction = null
                continue
            }

            val result = executor.execute(action, target, obs)
            listener?.onStep(step, obs, reply, parsed, decision, result)
            if (countsAsTier2) tier2ThisTurn++
            clearFailures()
            if (action == Action.NotifList && result.ok) {
                // Notification text is data for the model, not a line in the trailer or the audit.
                val lines = result.detail.lines().filter { it.isNotBlank() }
                notifListing = lines
                last = LastResult(action, true, "${lines.size} notifications listed")
            } else {
                last = LastResult(action, result.ok, result.detail)
            }
            lastAction = action
        }
    }

    companion object {
        const val KILLED = "killed"
        private const val OUTSIDE_GOAL_APPS = "outside-goal-apps"

        /** The verb keys the one Tier 1 card covers: every verb a `once` rule names. */
        fun tier1Verbs(pack: PolicyPack): Set<String> =
            pack.rules.filter { it.tier == Tier.ONCE_PER_TASK }.flatMap { it.`when`.verb.orEmpty() }.toSet()
    }
}
