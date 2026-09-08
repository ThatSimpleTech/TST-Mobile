package com.thatsimpletech.assist.core.net

/**
 * What goes in `Authorization: Bearer`. LiteLLM's master_key is the raw secret; a `sk-` prefix
 * or a leading `Bearer ` makes it look up a virtual key, hit no DB, and return 400
 * `no_db_connection`. OpenRouter keys keep their `sk-` prefix.
 */
object ProviderKey {
    fun sanitize(raw: String, stripSkPrefix: Boolean = false): String {
        var k = raw.trim().trim('\uFEFF')
        if (k.startsWith("Bearer ", ignoreCase = true)) k = k.substring(7).trim()
        if (stripSkPrefix && k.startsWith("sk-")) k = k.removePrefix("sk-").trim()
        return k
    }
}
