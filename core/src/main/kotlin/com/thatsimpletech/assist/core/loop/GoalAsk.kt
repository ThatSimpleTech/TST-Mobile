package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.policy.TextMatch

/**
 * `ask` that only restates the GOAL is a stall: the person already tapped Run.
 * Real asks (which Jerry, phone locked) still end the turn.
 */
object GoalAsk {
    private val preface = Regex(
        "^(should i|do you want me to|would you like me to|can i|shall i|may i|could i|want me to)\\s+",
    )

    fun restates(question: String, goal: String): Boolean {
        val q = strip(question)
        val g = strip(goal)
        if (q.isEmpty() || g.isEmpty()) return false
        if (q.contains(g) || g.contains(q)) return true
        val qw = words(q)
        val gw = words(g)
        if (gw.size < 2) return false
        val hit = qw.intersect(gw).size
        return hit >= 3 && hit * 2 >= gw.size
    }

    private fun strip(s: String): String =
        preface.replace(TextMatch.fold(s), "").trim('?', '.', '!', ' ')

    private fun words(s: String): Set<String> =
        s.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
}
