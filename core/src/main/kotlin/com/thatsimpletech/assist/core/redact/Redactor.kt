package com.thatsimpletech.assist.core.redact

/**
 * The one redactor for every output path: logs, audit rows, wire events, error text (plan §1,
 * "key never touches disk"). A port of TST Desk's `tstd/logging.py` `SECRET_PATTERNS`,
 * `redact_secrets` and `redact_structure`, kept pattern-for-pattern identical so a secret
 * shape the desktop scrubs is scrubbed here too.
 *
 * Thresholds follow core (`ghp_`/`github_pat_` need 36+ characters), not the desktop UI's
 * `redact.ts`, which loosens them to 20 as a second belt over already-redacted text. This is
 * the first belt, so it carries the real shapes: a 20-character floor would also swallow
 * ordinary words such as `ghp_` prefixed identifiers in screen text.
 */
object Redactor {
    const val MASK = "[REDACTED]"

    /** In desktop order. Each is a plain match-and-replace; none can match [MASK], so a second pass is a no-op. */
    val PATTERNS: List<Regex> = listOf(
        // OpenAI / OpenRouter / Anthropic keys, dashed forms included (sk-or-v1-…, sk-proj-…, sk-ant-…).
        Regex("sk-[a-zA-Z0-9][a-zA-Z0-9_-]{15,}"),
        // GitHub fine-grained PAT.
        Regex("github_pat_[a-zA-Z0-9_]{36,}"),
        // GitHub classic PAT.
        Regex("ghp_[a-zA-Z0-9]{36,}"),
        // AWS access key id.
        Regex("AKIA[0-9A-Z]{16}"),
        // PEM private key header. The trailing dashes survive, as on the desktop.
        Regex("-----BEGIN\\s+(RSA |EC |DSA |OPENSSH )?PRIVATE KEY"),
    )

    /** `redact_secrets`: every known credential shape in [s] becomes [MASK]. */
    fun text(s: String): String {
        var out = s
        for (p in PATTERNS) out = p.replace(out, MASK)
        return out
    }

    /**
     * `redact_structure`: walks a JSON-shaped value and scrubs every string, map keys included.
     * A credential as a key is unusual but must not reach disk either. Maps come back as
     * ordered maps and iterables as lists; anything else (numbers, booleans, null) is returned
     * untouched. Two keys that redact to the same text collapse into one, as they do in Python.
     */
    fun structure(value: Any?): Any? = when (value) {
        null -> null
        is String -> text(value)
        is CharSequence -> text(value.toString())
        is Map<*, *> -> {
            val out = LinkedHashMap<Any?, Any?>(value.size)
            for ((k, v) in value) out[structure(k)] = structure(v)
            out
        }
        is Iterable<*> -> value.map { structure(it) }
        is Array<*> -> value.map { structure(it) }
        else -> value
    }

    /**
     * What an exception may say in a log or audit row. HTTP clients quote the failing request,
     * headers included, so an exception message is a common place for the key to leak. The
     * class name is kept so a redacted message still says what went wrong.
     */
    fun throwableMessage(t: Throwable): String {
        val name = t::class.java.simpleName.ifEmpty { t::class.java.name }
        val message = t.message
        return text(if (message.isNullOrEmpty()) name else "$name: $message")
    }
}
