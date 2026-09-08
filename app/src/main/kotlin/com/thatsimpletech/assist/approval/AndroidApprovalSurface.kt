package com.thatsimpletech.assist.approval

import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.loop.ApprovalSurface
import com.thatsimpletech.assist.core.observe.UiNode
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plan §4: the overlay highlights the exact control and says the verb in plain words; a
 * notification carries Approve and Deny for when the overlay is not visible. Timeout is Deny.
 * The overlay is optional (no accessibility service means no overlay), the notification is not.
 */
class AndroidApprovalSurface(
    private val notifier: ApprovalNotifier,
    private val overlay: () -> OverlayCard?,
    private val timeoutSeconds: Int,
    private val auto: () -> Boolean = { false },
) : ApprovalSurface {

    override suspend fun requestTaskGrant(goal: String, apps: Set<String>, verbs: Set<String>): Boolean =
        ask(
            title = "Start this task?",
            body = "Goal: $goal\nApps: ${apps.joinToString(", ")}\nMay ${verbs.joinToString(", ")} inside those apps without asking again. Anything that sends, calls, pays, deletes, installs or shares will still ask.",
            target = null,
        )

    override suspend fun requestCard(action: Action, plainWords: String, target: UiNode?, reason: String): Boolean =
        ask(
            title = plainWords,
            body = buildString {
                if (target != null && target.label.isNotBlank()) append("On: \"").append(target.label).append("\"\n")
                if (reason.isNotBlank()) append("Why ask: ").append(reason)
            },
            target = target,
        )

    private suspend fun ask(title: String, body: String, target: UiNode?): Boolean {
        if (auto()) return true
        val (id, deferred) = ApprovalRequests.open()
        notifier.showApproval(id, title, body)
        val card = overlay()
        card?.show(title, body, target?.bounds, onApprove = { ApprovalRequests.resolve(id, true) }, onDeny = { ApprovalRequests.resolve(id, false) })
        return try {
            withTimeoutOrNull(timeoutSeconds * 1000L) { deferred.await() } ?: false
        } finally {
            ApprovalRequests.close(id)
            notifier.dismissApproval(id)
            card?.hide()
        }
    }
}
