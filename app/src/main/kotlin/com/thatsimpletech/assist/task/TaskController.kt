package com.thatsimpletech.assist.task

import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.a11y.AssistAccessibilityService
import com.thatsimpletech.assist.a11y.NodeExecutor
import com.thatsimpletech.assist.a11y.TreeObserver
import com.thatsimpletech.assist.core.grammar.ActionParser
import com.thatsimpletech.assist.core.loop.Outcome
import com.thatsimpletech.assist.core.loop.TaskRunner
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.observe.ObservationBuilder
import com.thatsimpletech.assist.core.policy.PolicyEnforcer
import com.thatsimpletech.assist.core.policy.PolicyPack
import com.thatsimpletech.assist.core.policy.TextMatch
import com.thatsimpletech.assist.kill.GlobalKillSwitch
import com.thatsimpletech.assist.notif.AssistNotificationListener
import java.util.UUID

/**
 * Wires one task run: observer and executor from the accessibility service, the approval
 * surface, the kill switch, the policy enforcer, the planner for the configured mode, the
 * meter, and the audit listener. Anything missing is reported in words, never guessed.
 */
object TaskController {
    suspend fun run(goal: String, onMeter: (String) -> Unit): String {
        val service = AssistAccessibilityService.instance
            ?: return "stopped: the screen driver (accessibility service) is off"
        if (GlobalKillSwitch.killed) return "stopped: kill switch is set; re-arm it in the app"

        val meter = CostTracker()
        val choice = Planners.forConfig(meter)
        val planner = choice.planner ?: return "stopped: ${choice.reason}"
        meter.addListener { onMeter(meterChip(meter)) }

        val pack = Graph.pack
        val enforcer = PolicyEnforcer(pack)
        val walker = service.walker
        val observer = TreeObserver(service, walker) { service.lastActivity }
        val executor = NodeExecutor(
            service = service, walker = walker, pack = pack,
            notifications = AssistNotificationListener.instance ?: AssistNotificationListener.unavailable,
        )
        val sessionId = UUID.randomUUID().toString()
        val audit = Graph.audit
        audit.startSession(sessionId, Graph.deviceId, choice.mode, goal)
        val runner = TaskRunner(
            observer = observer, planner = planner, executor = executor, approvals = Graph.approvals,
            kill = GlobalKillSwitch, enforcer = enforcer, builder = ObservationBuilder(),
            parser = ActionParser(), instructions = Graph.instructions(),
            listener = AuditListener(audit, sessionId, meter, Graph.clock),
        )
        onMeter(meterChip(meter))
        val goalApps = GoalApps.infer(goal, pack)
        return when (val outcome = runner.run(goal, goalApps)) {
            is Outcome.Done -> "done: ${outcome.summary}"
            is Outcome.Ask -> "question: ${outcome.question}"
            is Outcome.Stopped -> "stopped: ${outcome.reason}"
        } + "  ·  " + meterChip(meter)
    }

    /** The spend chip (plan §5): session, this turn, tokens. */
    fun meterChip(m: CostTracker): String =
        "\$%.4f session · \$%.4f turn · %d tokens".format(m.sessionCost(), m.turnCost(), m.sessionTokens())
}

/**
 * The goal's app set (the intent lock, plan §4). Until a task-planning step exists, the set
 * is every allowlisted app the goal names by label or alias. A goal that names none gets
 * an empty set: every app-scoped action is outside the lock and needs a card (TM-014).
 * A planner that emits the app set is M2.
 */
object GoalApps {
    fun infer(goal: String, pack: PolicyPack): Set<String> {
        val named = pack.apps.filter { app ->
            (listOf(app.label) + app.aliases).any { TextMatch.containsWord(goal, it) }
        }.mapTo(LinkedHashSet()) { it.pkg }
        if ("com.whatsapp" in named) named += "com.whatsapp.w4b"
        if ("com.whatsapp.w4b" in named) named += "com.whatsapp"
        return named
    }
}
