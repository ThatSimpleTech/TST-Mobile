package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.grammar.ActionParser
import com.thatsimpletech.assist.core.grammar.ParseResult
import com.thatsimpletech.assist.core.observe.LastResult
import com.thatsimpletech.assist.core.observe.Observation
import com.thatsimpletech.assist.core.observe.ObservationBuilder
import com.thatsimpletech.assist.core.observe.ObservationFormatter
import com.thatsimpletech.assist.core.observe.Spatial
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
    private val spend: SpendGuard? = null,
    private val validator: EndStateValidator? = null,
) {
    /** The kinds of model failure that end the run when they happen twice in a row. */
    private enum class Failure { PARSE, REFUSED, DENIED }

    suspend fun run(goal: String, goalApps: Set<String>): Outcome {
        val loops = LoopDetector(loopRepeatLimit)
        val tier1Verbs = tier1Verbs(enforcer.pack)

        var last: LastResult? = null
        var parseNote: String? = null
        var tier2Pending: Action? = null
        var lastAction: Action? = null
        var failure: Failure? = null
        var failures = 0

        var taskGranted = false
        val confirmedApps = LinkedHashSet<String>()
        var tier2ThisTurn = 0
        var drafted = false
        var submitted = false

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
            spend?.snapshot()?.takeIf { it.exceeded }?.let { snap ->
                val cap = snap.capUsd ?: 0.0
                return end(
                    Outcome.Paused(
                        reason = "the spend cap of \$${"%.2f".format(cap)} is reached",
                        spentUsd = snap.spentUsd,
                        capUsd = cap,
                    ),
                )
            }

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
                append(ObservationFormatter.format(obs, Trailer(goal, step, budget.limit, last, tier2Pending), parser.codec))
                // A parse error has no action to put in LAST, so the reason rides the STEP line by itself.
                parseNote?.let { append("   LAST: error ").append(ObservationFormatter.clean(it, 120)) }
            }
            val reply = planner.next(prompt)
            if (kill.killed) return end(Outcome.Stopped(KILLED))
            val parsed = parser.parse(reply, obs.hints) { x, y -> Spatial.nearestHint(obs, x, y) }

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

            if (action !is Action.More && action !is Action.Wait && loops.record(action.render())) {
                listener?.onStep(step, obs, reply, parsed, null, null)
                return end(Outcome.Stopped("looping: `${action.render()}` repeated ${loops.streak} times"))
            }

            when (action) {
                is Action.Done -> {
                    val submit = Spatial.trailingButton(obs)
                    if (drafted && !submitted && submit != null) {
                        listener?.onStep(step, obs, reply, parsed, null, null)
                        last = LastResult(action, ok = false, detail = "the box still has [${submit.hint}] ${submit.node.role.word} ${Spatial.at(submit.node, obs.display)} to its right; tap ${submit.hint} before done")
                        lastAction = action
                        continue
                    }
                    listener?.onStep(step, obs, reply, parsed, null, null)
                    if (validator == null) return end(Outcome.Done(action.summary))
                    return when (val v = validator.validate(goal, obs, goalApps)) {
                        is Validation.Pass -> end(Outcome.Done(action.summary))
                        is Validation.Fail -> end(validatorFail(v.reason))
                    }
                }
                is Action.Ask -> {
                    if (GoalAsk.restates(action.question, goal)) {
                        listener?.onStep(step, obs, reply, parsed, null, null)
                        last = LastResult(action, ok = false, detail = "the GOAL is already approved; take an action. ask is only for a missing fact")
                        lastAction = action
                        continue
                    }
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
            val decision = enforcer.decide(action, obs.app, target, obs.keyguard, obs.secure, task, obs.onQs)
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
            // C3: hint verbs were planned against one screen; execute only against that same
            // screen. Intent / device / partner / qs carry empty hints and do not target a node.
            if (action.hints.isNotEmpty() && observer.fingerprint() != obs.fingerprint) {
                listener?.onStep(step, obs, reply, parsed, decision, null)
                last = LastResult(action, ok = false, detail = "screen changed, look again")
                lastAction = null
                continue
            }

            val result = executor.execute(action, target, obs)
            listener?.onStep(step, obs, reply, parsed, decision, result)
            if (countsAsTier2) tier2ThisTurn++
            clearFailures()
            if (result.ok && action is Action.Type) drafted = true
            if (result.ok && action is Action.Tap && target != null &&
                Spatial.trailingButton(obs)?.node?.identity == target.identity) submitted = true
            last = LastResult(action, result.ok, result.detail)
            lastAction = action
        }
    }

    companion object {
        const val KILLED = "killed"
        private const val OUTSIDE_GOAL_APPS = "outside-goal-apps"

        /** The verb keys the one Tier 1 card covers: every verb a `once` rule names. */
        fun tier1Verbs(pack: PolicyPack): Set<String> =
            pack.rules.filter { it.tier == Tier.ONCE_PER_TASK }.flatMap { it.`when`.verb.orEmpty() }.toSet()

        /**
         * Q6: a failed validator is Ask, not Done. One return so flipping back to D4
         * (`Outcome.Stopped("end state: $reason")`) is a one-line change.
         */
        internal fun validatorFail(reason: String): Outcome =
            Outcome.Ask("The end state does not match the goal: $reason")
    }
}
