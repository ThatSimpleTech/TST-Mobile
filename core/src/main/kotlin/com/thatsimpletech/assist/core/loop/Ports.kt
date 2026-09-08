package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.observe.Observation
import com.thatsimpletech.assist.core.observe.Screen
import com.thatsimpletech.assist.core.observe.UiNode

/**
 * The seams between the platform-independent loop and the phone. The Android app implements
 * these; tests use fakes. Nothing here imports Android.
 */

/** Reads the screen. [fingerprint] must be cheap: it runs before every execution (stale check, C3). */
interface Observer {
    fun observe(): Screen
    fun fingerprint(): String
}

/** The brain. Gets the whole prompt, returns the model's reply text. */
interface Planner {
    suspend fun next(prompt: String): String

    /** One metered brain call, not a loop step. Default empty so ScriptedPlanner stays valid. */
    suspend fun planGoalApps(goal: String): Set<String> = emptySet()
}

data class ExecResult(val ok: Boolean, val detail: String = "") {
    companion object {
        val OK = ExecResult(true)
        fun error(detail: String) = ExecResult(false, detail)
    }
}

/** Performs one already-approved action against the screen it was planned on. */
interface Executor {
    suspend fun execute(action: Action, target: UiNode?, observation: Observation): ExecResult
}

/**
 * Where approvals are shown: the overlay card plus the notification with Approve and Deny.
 * Both calls return false on deny and on timeout; the surface owns the timeout.
 */
interface ApprovalSurface {
    /** The one Tier 1 card at task start: the goal, the apps, the verbs it covers. */
    suspend fun requestTaskGrant(goal: String, apps: Set<String>, verbs: Set<String>): Boolean

    /** A Tier 2 card for one action, with the target highlighted on screen. */
    suspend fun requestCard(action: Action, plainWords: String, target: UiNode?, reason: String): Boolean
}

/** The kill switch: persistent-notification action and Quick Settings tile. Polled before every step. */
interface KillSwitch {
    val killed: Boolean
}

/** Session spend vs the cap. The loop polls this before each planner call (TM-017). */
fun interface SpendGuard {
    fun snapshot(): SpendSnapshot
}

data class SpendSnapshot(val exceeded: Boolean, val spentUsd: Double, val capUsd: Double?)

/**
 * After `done`, compare the last screen to the goal (TM-018). Null on the runner keeps
 * today's [Outcome.Done]. Fail is [Outcome.Ask], not success (Q6).
 */
interface EndStateValidator {
    suspend fun validate(goal: String, last: Observation, goalApps: Set<String>): Validation
}

sealed class Validation {
    data object Pass : Validation()
    data class Fail(val reason: String) : Validation()
}
