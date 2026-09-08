package com.thatsimpletech.assist.core.loop

import com.thatsimpletech.assist.core.grammar.ActionParser
import com.thatsimpletech.assist.core.grammar.Quote
import com.thatsimpletech.assist.core.policy.PolicyPack

/**
 * Planner-emitted goal apps (TM-019). One `apps` line, same quoting as the grammar;
 * labels and packages are intersected with the allowlist. Invented names are dropped.
 */
object GoalAppsPlanner {
    private val lexer = ActionParser()

    fun parse(raw: String, pack: PolicyPack): Set<String> {
        val line = lexer.firstLine(raw) ?: return emptySet()
        val tokens = try {
            lexer.tokenize(line)
        } catch (_: IllegalArgumentException) {
            return emptySet()
        }
        if (tokens.isEmpty()) return emptySet()
        val head = tokens.first()
        if (head.quoted || !head.text.equals("apps", ignoreCase = true)) return emptySet()
        val args = tokens.drop(1)
        if (args.size == 1 && !args[0].quoted && args[0].text.equals("none", ignoreCase = true)) {
            return emptySet()
        }
        val pkgs = LinkedHashSet<String>()
        for (t in args) {
            if (!t.quoted) continue
            resolve(t.text, pack)?.let { pkgs += it }
        }
        return pkgs
    }

    fun prompt(goal: String, allowlistedLabels: List<String>): String {
        val labels = allowlistedLabels.joinToString(" ") { Quote.q(it) }
        return "Goal: $goal\nAllowlist: $labels\nReply with apps \"Label\" … or apps none."
    }

    /** Label/alias via [PolicyPack.resolveOpen], or an exact allowlisted package name. */
    private fun resolve(token: String, pack: PolicyPack): String? =
        pack.resolveOpen(token)?.pkg ?: pack.app(token)?.pkg
}
