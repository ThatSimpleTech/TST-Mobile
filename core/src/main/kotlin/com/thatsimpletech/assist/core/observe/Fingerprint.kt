package com.thatsimpletech.assist.core.observe

import java.security.MessageDigest

/**
 * Identifies "the screen the model was looking at". Built from the app, the activity and
 * the identities of the selected nodes, not their labels, so a ticking clock does not
 * invalidate a hint but a navigation does. Actions carry the fingerprint they were
 * planned against; the executor refuses a mismatch (stale hint).
 */
object Fingerprint {
    fun of(app: String, activity: String, identities: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(app.toByteArray())
        md.update(0)
        md.update(activity.toByteArray())
        md.update(0)
        for (id in identities) {
            md.update(id.toByteArray())
            md.update(0)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The four hex characters shown on the SCREEN line. */
    fun short(full: String): String = full.take(4)
}
