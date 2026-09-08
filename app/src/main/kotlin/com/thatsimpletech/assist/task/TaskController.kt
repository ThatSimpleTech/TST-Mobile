package com.thatsimpletech.assist.task

import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.a11y.AssistAccessibilityService
import com.thatsimpletech.assist.a11y.NodeExecutor
import com.thatsimpletech.assist.a11y.TreeObserver
import com.thatsimpletech.assist.approval.OverlayMeter
import com.thatsimpletech.assist.core.grammar.ActionParser
import com.thatsimpletech.assist.core.loop.GoalApps
import com.thatsimpletech.assist.core.loop.Outcome
import com.thatsimpletech.assist.core.loop.SpendGuard
import com.thatsimpletech.assist.core.loop.SpendSnapshot
import com.thatsimpletech.assist.core.loop.TaskRunner
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.meter.MeterText
import com.thatsimpletech.assist.core.observe.ObservationBuilder
import com.thatsimpletech.assist.core.policy.PolicyEnforcer
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
        val audit = Graph.audit
        // Seed before the first record of this session so audit rows are not double-counted (TM-025).
        meter.seedDayCost(audit.daySpend(Graph.clock()))
        val choice = Planners.forConfig(meter)
        val planner = choice.planner ?: return "stopped: ${choice.reason}"
        val capUsd = Graph.config.spendCapUsd
        val overlay = OverlayMeter(service)

        fun chip(paused: Boolean = false) = MeterText.chip(meter.snapshot(), capUsd, paused)

        try {
            overlay.show(chip())
            meter.addListener {
                val text = chip()
                overlay.update(text)
                onMeter(text)
            }

            val pack = Graph.pack
            val enforcer = PolicyEnforcer(pack)
            val walker = service.walker
            val observer = TreeObserver(service, walker) { service.lastActivity }
            val executor = NodeExecutor(
                service = service, walker = walker, pack = pack,
                notifications = AssistNotificationListener.instance ?: AssistNotificationListener.unavailable,
            )
            val sessionId = UUID.randomUUID().toString()
            audit.startSession(sessionId, Graph.deviceId, choice.mode, goal)
            val codec = Graph.provider.codec()
            val runner = TaskRunner(
                observer = observer, planner = planner, executor = executor, approvals = Graph.approvals,
                kill = GlobalKillSwitch, enforcer = enforcer, builder = ObservationBuilder(),
                parser = ActionParser(codec), instructions = Graph.instructions(codec),
                listener = AuditListener(audit, sessionId, meter, Graph.clock),
                spend = SpendGuard {
                    SpendSnapshot(
                        exceeded = meter.capExceeded(capUsd),
                        spentUsd = meter.sessionCost(),
                        capUsd = capUsd,
                    )
                },
                validator = Planners.endStateValidator(meter),
            )
            onMeter(chip())
            val emitted = planner.planGoalApps(goal)
            val goalApps = GoalApps.merge(emitted, GoalApps.infer(goal, pack))
            val outcome = runner.run(goal, goalApps)
            val paused = outcome is Outcome.Paused
            val last = chip(paused)
            overlay.update(last)
            onMeter(last)
            return when (outcome) {
                is Outcome.Done -> "done: ${outcome.summary}"
                is Outcome.Ask -> "question: ${outcome.question}"
                is Outcome.Stopped -> "stopped: ${outcome.reason}"
                is Outcome.Paused -> "paused: ${outcome.reason}"
            } + "  ·  " + last
        } finally {
            overlay.hide()
        }
    }

    /** The spend chip (plan §5): turn, session, day, by tier, remaining. */
    fun meterChip(m: CostTracker, paused: Boolean = false): String =
        MeterText.chip(m.snapshot(), Graph.config.spendCapUsd, paused)
}
