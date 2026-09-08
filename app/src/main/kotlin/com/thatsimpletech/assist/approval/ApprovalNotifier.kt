package com.thatsimpletech.assist.approval

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * The notification half of the approval surface (plan §4): Approve and Deny as actions, for
 * when the overlay is not visible. Also the persistent task notification with Stop.
 */
class ApprovalNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "One notification per action that needs your OK"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TASK, "Running task", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the running task, its spend, and the Stop button"
            },
        )
    }

    fun showApproval(requestId: Long, title: String, body: String) {
        val approve = pending(requestId, ApprovalReceiver.ACTION_APPROVE, 1)
        val deny = pending(requestId, ApprovalReceiver.ACTION_DENY, 2)
        val n = Notification.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(Icon.createWithResource(context, android.R.drawable.ic_dialog_info))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.checkbox_on_background), "Approve", approve).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel), "Deny", deny).build())
            .build()
        nm.notify(approvalNotificationId(requestId), n)
    }

    fun dismissApproval(requestId: Long) {
        nm.cancel(approvalNotificationId(requestId))
    }

    /** The persistent notification the foreground service posts. [meter] is the spend chip text. */
    fun taskNotification(goal: String, meter: String): Notification {
        val kill = PendingIntent.getBroadcast(
            context, 3, Intent(context, ApprovalReceiver::class.java).setAction(ApprovalReceiver.ACTION_KILL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_TASK)
            .setSmallIcon(Icon.createWithResource(context, android.R.drawable.ic_menu_manage))
            .setContentTitle("EZER: $goal")
            .setContentText(meter)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_lock_power_off), "Stop", kill).build())
            .build()
    }

    fun updateTask(goal: String, meter: String) {
        nm.notify(TASK_NOTIFICATION_ID, taskNotification(goal, meter))
    }

    private fun pending(requestId: Long, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, (requestId * 10 + code).toInt(),
            Intent(context, ApprovalReceiver::class.java).setAction(action).putExtra(ApprovalReceiver.EXTRA_ID, requestId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun approvalNotificationId(requestId: Long): Int = 10_000 + (requestId % 10_000).toInt()

    companion object {
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_TASK = "task"
        const val TASK_NOTIFICATION_ID = 1
    }
}
