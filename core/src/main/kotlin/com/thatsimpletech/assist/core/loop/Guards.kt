package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.policy.PolicyPack

/**
 * Plan M2: a weak model must not run forever. The budget counts model calls per task;
 * the value comes from the policy pack (step_budget, default 12), never from the model.
 */
class StepBudget(val limit: Int) {
    init {
        require(limit >= 1) { "step budget must be >= 1" }
    }

    /** True when [step] (1-based) is still inside the budget. */
    fun allows(step: Int): Boolean = step <= limit

    companion object {
        fun of(pack: PolicyPack) = StepBudget(pack.stepBudget)
    }
}

/**
 * Plan M2: the same rendered action [limit] times in a row means the model is stuck.
 * Rendered text is the key so `tap 4` and `tap 4` match while `tap 4` and `tap 5` do not.
 * One instance per run; only parsed actions feed it, so a garbage reply between two
 * identical actions neither breaks nor extends the streak.
 */
class LoopDetector(val limit: Int) {
    init {
        require(limit >= 1) { "loop repeat limit must be >= 1" }
    }

    private var lastRendered: String? = null
    var streak: Int = 0
        private set

    /** Records one action. Returns true when this action completes a streak of [limit]. */
    fun record(rendered: String): Boolean {
        if (rendered == lastRendered) streak++ else {
            lastRendered = rendered
            streak = 1
        }
        return streak >= limit
    }

    companion object {
        fun of(pack: PolicyPack) = LoopDetector(pack.loopRepeatLimit)
    }
}
