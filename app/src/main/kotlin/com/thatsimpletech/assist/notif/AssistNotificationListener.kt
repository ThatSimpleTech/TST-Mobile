package com.thatsimpletech.assist.notif

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.thatsimpletech.assist.a11y.NodeExecutor
import com.thatsimpletech.assist.core.loop.ExecResult
import com.thatsimpletech.assist.core.observe.ObservationFormatter

/**
 * Notification read and inline reply (plan §2). Ids are short (n1, n2, ...) and stable for the
 * life of the notification. Text is rendered through the same escaping as screen labels: it is
 * data, and the policy gates every reply as Tier 2.
 */
class AssistNotificationListener : NotificationListenerService(), NodeExecutor.NotificationActions,
    com.thatsimpletech.assist.core.loop.NotificationDirectory {
    private val ids = LinkedHashMap<String, String>() // key -> nId
    private var next = 1

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { ids.remove(it.key) }
    }

    private fun idFor(sbn: StatusBarNotification): String = ids.getOrPut(sbn.key) { "n${next++}" }

    private fun current(): List<StatusBarNotification> =
        (activeNotifications ?: emptyArray()).filter { it.isClearable || it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) != null }

    /** The posting package, for the policy: notification verbs are judged by it. */
    override fun packageOf(id: String): String? = current().firstOrNull { idFor(it) == id }?.packageName

    override fun list(): List<String> = current().map { sbn ->
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val canReply = sbn.notification.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
        "[${idFor(sbn)}] ${sbn.packageName} ${ObservationFormatter.quote(title)} ${ObservationFormatter.quote(text)}${if (canReply) " reply=yes" else ""}"
    }

    override fun reply(id: String, text: String): ExecResult {
        val sbn = current().firstOrNull { idFor(it) == id } ?: return ExecResult.error("no notification $id")
        val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: return ExecResult.error("notification $id has no reply action")
        val inputs = action.remoteInputs
        val intent = Intent()
        val results = Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(this, 0, intent)
            ExecResult.OK
        } catch (e: Exception) {
            ExecResult.error("reply failed")
        }
    }

    override fun open(id: String): ExecResult {
        val sbn = current().firstOrNull { idFor(it) == id } ?: return ExecResult.error("no notification $id")
        val pi = sbn.notification.contentIntent ?: return ExecResult.error("notification $id cannot be opened")
        return try {
            pi.send()
            ExecResult.OK
        } catch (e: Exception) {
            ExecResult.error("open failed")
        }
    }

    companion object {
        @Volatile
        var instance: AssistNotificationListener? = null
            private set

        /** What the executor gets when notification access is off: honest errors, not silence. */
        val unavailable = object : NodeExecutor.NotificationActions {
            override fun list() = listOf("notification access is off")
            override fun reply(id: String, text: String) = ExecResult.error("notification access is off")
            override fun open(id: String) = ExecResult.error("notification access is off")
        }
    }
}
