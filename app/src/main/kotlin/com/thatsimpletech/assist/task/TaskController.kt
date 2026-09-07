package com.thatsimpletech.assist.task

import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.a11y.AssistAccessibilityService
import com.thatsimpletech.assist.a11y.NodeExecutor
import com.thatsimpletech.assist.a11y.TreeObserver
import com.thatsimpletech.assist.core.grammar.ActionParser
import com.thatsimpletech.assist.core.loop.Outcome
import com.thatsimpletech.assist.core.loop.Planner
import com.thatsimpletech.assist.core.loop.RunListener
import com.thatsimpletech.assist.core.loop.TaskRunner
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.observe.ObservationBuilder
import com.thatsimpletech.assist.core.policy.PolicyEnforcer
import com.thatsimpletech.assist.core.steering.DefaultInstructions
import com.thatsimpletech.assist.kill.GlobalKillSwitch
import com.thatsimpletech.assist.notif.AssistNotificationListener

/**
 * Wires one task run: observer and executor from the accessibility service, the approval
 * surface, the kill switch, the policy enforcer, the planner for the configured mode, the
 * meter, and the audit listener. Anything missing is reported in words, never guessed.
 */
object TaskController {
    /** The planner for the configured mode, or null with a reason. Set by the app graph. */
    @Volatile
    var plannerFactory: (CostTracker) -> Planner? = { null }

    /** Extra run listeners (audit, UI). */
    @Volatile
    var listeners: List<(CostTracker) -> RunListener> = emptyList()

    suspend fun run(goal: String, onMeter: (String) -> Unit): String {
        val service = AssistAccessibilityService.instance
            ?: return "stopped: the screen driver (accessibility service) is off"
        if (GlobalKillSwitch.killed) return "stopped: kill switch is set; re-arm it in the app"

        val meter = CostTracker()
        val planner = plannerFactory(meter)
            ?: return "stopped: no model configured (add a provider key, or a home daemon)"
        meter.addListener { onMeter(meterChip(meter)) }

        val pack = Graph.pack
        val enforcer = PolicyEnforcer(pack)
        val walker = service.walker
        val observer = TreeObserver(service, walker) { service.lastActivity }
        val executor = NodeExecutor(
            service = service, walker = walker, pack = pack,
            notifications = AssistNotificationListener.instance ?: AssistNotificationListener.unavailable,
        )
        val listener = Fanout(listeners.map { it(meter) })
        val runner = TaskRunner(
            observer = observer, planner = planner, executor = executor, approvals = Graph.approvals,
            kill = GlobalKillSwitch, enforcer = enforcer, builder = ObservationBuilder(),
            parser = ActionParser(), instructions = DefaultInstructions.load(), listener = listener,
        )
        onMeter(meterChip(meter))
        val goalApps = GoalApps.infer(goal, pack)
        return when (val outcome = runner.run(goal, goalApps)) {
            is Outcome.Done -> "done: ${outcome.summary}"
            is Outcome.Ask -> "question: ${outcome.question}"
            is Outcome.Stopped -> "stopped: ${outcome.reason}"
        } + "  ·  " + meterChip(meter)
    }

    /** The spend chip: session so far, this turn, by tier when there is more than one. */
    fun meterChip(m: CostTracker): String {
        val session = m.sessionCost()
        val turn = m.turnCost()
        return "\$%.4f session · \$%.4f turn · %d tokens".format(session, turn, m.sessionTokens())
    }

    private class Fanout(private val all: List<RunListener>) : RunListener {
        override fun onStep(
            step: Int, observation: com.thatsimpletech.assist.core.observe.Observation, reply: String,
            parsed: com.thatsimpletech.assist.core.grammar.ParseResult,
            decision: com.thatsimpletech.assist.core.policy.Decision?, result: com.thatsimpletech.assist.core.loop.ExecResult?,
        ) = all.forEach { it.onStep(step, observation, reply, parsed, decision, result) }

        override fun onApproval(kind: com.thatsimpletech.assist.core.loop.ApprovalKind, approved: Boolean) =
            all.forEach { it.onApproval(kind, approved) }

        override fun onEnd(outcome: Outcome) = all.forEach { it.onEnd(outcome) }
    }
}

/**
 * The goal's app set (the intent lock, plan §4). Until a task-planning step exists, the set
 * is every allowlisted app the goal names by label or alias; a goal that names none gets the
 * whole allowlist, so the lock still holds at the allowlist boundary.
 */
object GoalApps {
    fun infer(goal: String, pack: com.thatsimpletech.assist.core.policy.PolicyPack): Set<String> {
        val folded = com.thatsimpletech.assist.core.policy.TextMatch.fold(goal)
        val named = pack.apps.filter { app ->
            (listOf(app.label) + app.aliases).any { com.thatsimpletech.assist.core.policy.TextMatch.containsWord(folded, it) }
        }.mapTo(LinkedHashSet()) { it.pkg }
        return if (named.isEmpty()) pack.packages else named
    }
}
