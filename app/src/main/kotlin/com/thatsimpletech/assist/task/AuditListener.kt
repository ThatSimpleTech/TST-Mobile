package com.thatsimpletech.assist.task

import com.thatsimpletech.assist.core.audit.ActionStatus
import com.thatsimpletech.assist.core.audit.Approval
import com.thatsimpletech.assist.core.audit.ApprovalKind
import com.thatsimpletech.assist.core.audit.ApprovalOutcome
import com.thatsimpletech.assist.core.audit.AuditStore
import com.thatsimpletech.assist.core.grammar.ParseResult
import com.thatsimpletech.assist.core.loop.ExecResult
import com.thatsimpletech.assist.core.loop.Outcome
import com.thatsimpletech.assist.core.loop.RunListener
import com.thatsimpletech.assist.core.meter.CostTracker
import com.thatsimpletech.assist.core.observe.Observation
import com.thatsimpletech.assist.core.policy.Decision
import com.thatsimpletech.assist.core.policy.Gate
import com.thatsimpletech.assist.core.policy.PolicyEnforcer
import com.thatsimpletech.assist.core.policy.Tier

/**
 * Every step, approval and model call becomes an INSERT. Text goes through the store's
 * redactor. A reply that did not parse is still a row: what the model said is part of the
 * record, with status refused and rule "parse".
 */
class AuditListener(
    private val store: AuditStore,
    private val sessionId: String,
    meter: CostTracker,
    private val clock: () -> Double,
) : RunListener {
    private var pendingApprovalSince: Double? = null

    init {
        meter.addListener { rec ->
            store.recordModelCall(
                sessionId = sessionId, turnId = null, tier = rec.tier.word, model = rec.model,
                promptTokens = rec.promptTokens, cachedPromptTokens = rec.cachedPromptTokens,
                completionTokens = rec.completionTokens, cost = rec.cost.total, isClassifier = rec.classifier,
            )
        }
    }

    override fun onStep(step: Int, observation: Observation, reply: String, parsed: ParseResult, decision: Decision?, result: ExecResult?) {
        when (parsed) {
            is ParseResult.Error -> store.recordAction(
                sessionId = sessionId, step = step, verb = "parse", action = reply.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "",
                app = observation.app, targetLabel = null, targetId = null,
                tier = Tier.REFUSED, rule = "parse", gate = Gate.REFUSE, approval = null,
                status = ActionStatus.REFUSED, detail = parsed.reason, fingerprint = observation.fp,
            )
            is ParseResult.Ok -> {
                val action = parsed.action
                val target = action.hints.firstOrNull()?.let { observation.node(it) }
                val status = when {
                    decision == null -> ActionStatus.SKIPPED
                    decision.gate == Gate.REFUSE -> ActionStatus.REFUSED
                    result == null -> ActionStatus.SKIPPED
                    result.ok -> ActionStatus.SUCCESS
                    else -> ActionStatus.ERROR
                }
                val approval = when {
                    decision == null -> null
                    decision.tier == Tier.ONCE_PER_TASK && result != null -> Approval.GRANTED
                    decision.tier == Tier.EVERY_TIME && result != null -> Approval.APPROVED
                    decision.tier == Tier.EVERY_TIME && result == null -> Approval.DENIED
                    else -> null
                }
                store.recordAction(
                    sessionId = sessionId, step = step, verb = PolicyEnforcer.verbKey(action), action = action.render(),
                    app = observation.app, targetLabel = target?.label, targetId = target?.resourceId,
                    tier = decision?.tier ?: Tier.SILENT, rule = decision?.rule ?: "terminal", gate = decision?.gate ?: Gate.PROCEED,
                    approval = approval, status = status,
                    detail = (result?.detail?.ifBlank { null } ?: decision?.reason?.ifBlank { null })
                        ?.let { com.thatsimpletech.assist.core.observe.ObservationFormatter.clean(it, 200) },
                    fingerprint = observation.fp,
                )
            }
        }
    }

    override fun onApproval(kind: com.thatsimpletech.assist.core.loop.ApprovalKind, approved: Boolean) {
        val since = pendingApprovalSince ?: clock()
        pendingApprovalSince = null
        store.recordApproval(
            sessionId = sessionId, actionId = null,
            kind = if (kind == com.thatsimpletech.assist.core.loop.ApprovalKind.TASK_GRANT) ApprovalKind.TASK_GRANT else ApprovalKind.CARD,
            outcome = if (approved) ApprovalOutcome.APPROVED else ApprovalOutcome.DENIED,
            requestedAt = since,
        )
    }

    override fun onEnd(outcome: Outcome) {
        val (status, detail) = when (outcome) {
            is Outcome.Done -> ActionStatus.SUCCESS to outcome.summary
            is Outcome.Ask -> ActionStatus.SKIPPED to outcome.question
            is Outcome.Stopped -> (if (outcome.reason == "killed") ActionStatus.KILLED else ActionStatus.ERROR) to outcome.reason
        }
        store.recordAction(
            sessionId = sessionId, step = 0, verb = "end", action = "end", app = "",
            targetLabel = null, targetId = null, tier = Tier.SILENT, rule = "end", gate = Gate.PROCEED,
            approval = null, status = status, detail = detail,
        )
    }
}
