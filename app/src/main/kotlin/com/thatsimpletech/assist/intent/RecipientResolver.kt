package com.thatsimpletech.assist.intent

import com.thatsimpletech.assist.core.intent.ContactPick
import com.thatsimpletech.assist.core.loop.ExecResult

/** Name → one number for `call` / `text` / `whatsapp`. Numbers pass through. */
class RecipientResolver(private val contacts: DeviceContacts) {
    fun resolve(raw: String): ContactPick.Decision {
        val q = raw.trim()
        if (q.isEmpty()) return ContactPick.Decision.None
        if (ContactPick.looksLikeNumber(q)) return ContactPick.Decision.Ready(ContactPick.digitsOf(q), name = null)
        if (!contacts.granted()) return ContactPick.Decision.NeedPermission
        return ContactPick.choose(q, contacts.phones())
    }

    companion object {
        fun error(decision: ContactPick.Decision, query: String): ExecResult =
            ExecResult.error(ContactPick.explain(decision, query))
    }
}
