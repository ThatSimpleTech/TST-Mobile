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

    /**
     * The fingerprint of a selected node list. Actionable nodes contribute their cleaned label
     * and checked state as well as their identity: a recycled list row keeps its identity when
     * its contents change, and an approved tap must not land on the new contents. Text nodes
     * contribute identity only, so a ticking clock does not invalidate every hint.
     */
    fun ofNodes(app: String, activity: String, selected: List<UiNode>): String =
        of(app, activity, selected.map { contentKey(it) })

    fun contentKey(n: UiNode): String =
        if (n.actionable) n.identity + "#" + ObservationFormatter.clean(n.label, 40) + (if (n.checkable) "#" + n.checked else "")
        else n.identity

    /** The four hex characters shown on the SCREEN line. */
    fun short(full: String): String = full.take(4)
}
