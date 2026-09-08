package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.policy.PolicyPack
import com.thatsimpletech.assist.core.policy.TextMatch

/**
 * The goal's app set (the intent lock, plan §4). Production prefers planner-emitted
 * packages ([merge]); [infer] is the Q8 fallback when the planner returns none (TM-019).
 * A goal that names no allowlisted label stays empty: the first app-scoped action is a
 * card (TM-014). Never the whole allowlist.
 */
object GoalApps {
    fun infer(goal: String, pack: PolicyPack): Set<String> {
        val named = pack.apps.filter { app ->
            (listOf(app.label) + app.aliases).any { TextMatch.containsWord(goal, it) }
        }.mapTo(LinkedHashSet()) { it.pkg }
        return if (named.isEmpty()) emptySet() else named
    }

    /** Q8: prefer the planner; infer only when it returned empty. */
    fun merge(emitted: Set<String>, inferred: Set<String>): Set<String> =
        if (emitted.isNotEmpty()) emitted else inferred
}
