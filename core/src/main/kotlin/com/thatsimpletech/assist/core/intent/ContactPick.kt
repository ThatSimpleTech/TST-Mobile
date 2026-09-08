package com.thatsimpletech.assist.core.intent

import com.thatsimpletech.assist.core.grammar.Quote
import com.thatsimpletech.assist.core.policy.TextMatch

/**
 * Pick one phone number from a name or a raw number (TM-028). Pure: the app supplies
 * the contact rows. 7+ digits in the query is a number, not a name.
 */
object ContactPick {
    data class Hit(val name: String, val number: String, val mobile: Boolean = false)

    sealed class Decision {
        data class Ready(val digits: String, val name: String?) : Decision()
        data object None : Decision()
        data object NeedPermission : Decision()
        data class Ambiguous(val names: List<String>) : Decision()
    }

    fun digitsOf(raw: String): String = raw.filter { it.isDigit() }

    fun looksLikeNumber(raw: String): Boolean = digitsOf(raw).length >= 7

    fun choose(query: String, hits: List<Hit>): Decision {
        val q = query.trim()
        if (q.isEmpty()) return Decision.None
        if (looksLikeNumber(q)) return Decision.Ready(digitsOf(q), name = null)
        val needle = TextMatch.fold(q)
        if (needle.isEmpty()) return Decision.None
        val exact = hits.filter { TextMatch.fold(it.name) == needle }
        val word = hits.filter { TextMatch.containsWord(it.name, needle) }
        val pool = if (exact.isNotEmpty()) exact else word
        val unique = collapse(pool)
        return when {
            unique.isEmpty() -> Decision.None
            unique.size == 1 -> ready(unique[0])
            unique.map { TextMatch.fold(it.name) }.distinct().size == 1 -> ready(prefer(unique))
            else -> Decision.Ambiguous(unique.map { it.name }.distinct())
        }
    }

    fun explain(decision: Decision, query: String): String = when (decision) {
        is Decision.Ready -> "internal: number was ready"
        Decision.None -> "no contact matching ${Quote.q(query)}; use a number or open the app"
        Decision.NeedPermission -> "contacts permission is off"
        is Decision.Ambiguous -> {
            val shown = decision.names.take(5).joinToString(", ")
            "several contacts matching ${Quote.q(query)} ($shown); ask which one"
        }
    }

    private fun ready(hit: Hit): Decision.Ready = Decision.Ready(digitsOf(hit.number), hit.name)

    private fun collapse(hits: List<Hit>): List<Hit> {
        val out = ArrayList<Hit>()
        for (h in hits) {
            if (digitsOf(h.number).isEmpty()) continue
            val i = out.indexOfFirst { sameNumber(it.number, h.number) }
            if (i < 0) out += h else out[i] = merge(out[i], h)
        }
        return out
    }

    private fun merge(a: Hit, b: Hit): Hit {
        val number = if (digitsOf(b.number).length > digitsOf(a.number).length) b.number else a.number
        return Hit(name = a.name, number = number, mobile = a.mobile || b.mobile)
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val da = digitsOf(a)
        val db = digitsOf(b)
        if (da.isEmpty() || db.isEmpty()) return false
        if (da == db) return true
        return da.length >= 10 && db.length >= 10 && da.takeLast(10) == db.takeLast(10)
    }

    private fun prefer(hits: List<Hit>): Hit =
        hits.firstOrNull { it.mobile } ?: hits.maxByOrNull { digitsOf(it.number).length } ?: hits.first()
}
