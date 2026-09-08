package com.thatsimpletech.assist.task

import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.a11y.AssistAccessibilityService
import com.thatsimpletech.assist.a11y.EmptyObserver
import com.thatsimpletech.assist.a11y.NodeExecutor
import com.thatsimpletech.assist.a11y.TreeObserver
import com.thatsimpletech.assist.approval.OverlayMeter
import com.thatsimpletech.assist.core.grammar.ActionParser
import com.thatsimpletech.assist.core.loop.GoalApps
import com.thatsimpletech.assist.core.loop.Observer
import com.thatsimpletech.assist.core.loop.Outcome
import com.thatsimpletech.assist.core.loop.SpendGuard
import com.thatsimpletech.assist.core.loop.SpendSnapshot
import com.thatsimpletech.assist.core.loop.TaskRunner
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.meter.MeterText
import com.thatsimpletech.assist.core.observe.ObservationBuilder
import com.thatsimpletech.assist.core.policy.PolicyEnforcer
import com.thatsimpletech.assist.device.DeviceControls
import com.thatsimpletech.assist.intent.IntentExecutor
import com.thatsimpletech.assist.intent.PermissionGate
import com.thatsimpletech.assist.kill.GlobalKillSwitch
import com.thatsimpletech.assist.media.SessionMedia
import com.thatsimpletech.assist.notif.AssistNotificationListener
import com.thatsimpletech.assist.partner.AppFunctionExecutor
import com.thatsimpletech.assist.partner.PartnerRouter
import java.util.UUID

object TaskController {
    suspend fun run(goal: String, onMeter: (String) -> Unit): String {
        if (GlobalKillSwitch.killed) return "stopped: kill switch is set; re-arm it in the app"
        val service = AssistAccessibilityService.instance
        val appContext = Graph.app

        val meter = CostTracker()
        val audit = Graph.audit
        meter.seedDayCost(audit.daySpend(Graph.clock()))
        val choice = Planners.forConfig(meter)
        val planner = choice.planner ?: return "stopped: ${choice.reason}"
        val capUsd = Graph.config.spendCapUsd
        val overlay = service?.let { OverlayMeter(it) }

        fun chip(paused: Boolean = false) = MeterText.chip(meter.snapshot(), capUsd, paused)

        try {
            overlay?.show(chip())
            meter.addListener {
                val text = chip()
                overlay?.update(text)
                onMeter(text)
            }

            val pack = Graph.pack
            val enforcer = PolicyEnforcer(pack)
            val intents = IntentExecutor(appContext)
            val devices = DeviceControls(appContext, PermissionGate(appContext))
            val partners = PartnerRouter(AppFunctionExecutor(appContext), intents)
            val media = SessionMedia(appContext)
            val observer: Observer
            val executor: NodeExecutor
            if (service != null) {
                val walker = service.walker
                observer = TreeObserver(service, walker) { service.lastActivity }
                executor = NodeExecutor(
                    context = service, pack = pack,
                    notifications = AssistNotificationListener.instance ?: AssistNotificationListener.unavailable,
                    intents = intents, devices = devices, partners = partners, media = media,
                    service = service, walker = walker,
                )
            } else {
                observer = EmptyObserver(appContext)
                executor = NodeExecutor(
                    context = appContext, pack = pack,
                    notifications = AssistNotificationListener.instance ?: AssistNotificationListener.unavailable,
                    intents = intents, devices = devices, partners = partners, media = media,
                )
            }
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
            val inferred = GoalApps.infer(goal, pack)
            val goalApps = GoalApps.merge(emitted, inferred).toMutableSet()
            if ("com.whatsapp" in goalApps) goalApps += "com.whatsapp.w4b"
            if ("com.whatsapp.w4b" in goalApps) goalApps += "com.whatsapp"
            val outcome = runner.run(goal, goalApps)
            val paused = outcome is Outcome.Paused
            val last = chip(paused)
            overlay?.update(last)
            onMeter(last)
            return when (outcome) {
                is Outcome.Done -> "done: ${outcome.summary}"
                is Outcome.Ask -> if (isCallFailure(outcome.question)) "stopped: ${outcome.question}" else "question: ${outcome.question}"
                is Outcome.Stopped -> "stopped: ${outcome.reason}"
                is Outcome.Paused -> "paused: ${outcome.reason}"
            } + "  ·  " + last
        } finally {
            overlay?.hide()
        }
    }

    fun meterChip(m: CostTracker, paused: Boolean = false): String =
        MeterText.chip(m.snapshot(), Graph.config.spendCapUsd, paused)

    private fun isCallFailure(q: String): Boolean {
        val f = q.lowercase()
        return "timed out" in f || "timeout" in f || "model call failed" in f || "empty action" in f
    }
}
