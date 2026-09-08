package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.grammar.ParseResult
import com.thatsimpletech.assist.core.observe.Observation
import com.thatsimpletech.assist.core.policy.Decision

/** How a task run ended. The app turns every one of these into words on the screen. */
sealed class Outcome {
    /** The model said `done`. */
    data class Done(val summary: String) : Outcome()

    /** The model said `ask`; the turn ends and the person answers. */
    data class Ask(val question: String) : Outcome()

    /**
     * The loop stopped on its own: kill switch, step budget, loop detector, a second
     * consecutive model failure, a denied grant. [reason] is plain words for the person.
     */
    data class Stopped(val reason: String) : Outcome()

    /**
     * Session spend reached the cap (TM-017). No further provider call. Raising the cap
     * and running again is a new task; there is no in-session resume in M2.
     */
    data class Paused(val reason: String, val spentUsd: Double, val capUsd: Double) : Outcome()
}

enum class ApprovalKind { TASK_GRANT, CARD }

/**
 * Sees every step as it happens. The audit module implements this later; nothing here
 * names an audit type, so the loop stays testable without a database. All methods are
 * no-ops by default.
 */
interface RunListener {
    /**
     * One model call. [decision] is null when the reply did not parse or was `done`, `ask`
     * or `more`; [result] is null when nothing was executed (refused, denied, stale, killed).
     */
    fun onStep(step: Int, observation: Observation, reply: String, parsed: ParseResult, decision: Decision?, result: ExecResult?) {}

    fun onApproval(kind: ApprovalKind, approved: Boolean) {}

    fun onEnd(outcome: Outcome) {}
}
