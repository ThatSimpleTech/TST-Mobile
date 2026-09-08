package com.thatsimpletech.assist.approval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thatsimpletech.assist.kill.GlobalKillSwitch

/** Notification actions land here: Approve, Deny, and the Stop action of the task notification. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_APPROVE -> ApprovalRequests.resolve(intent.getLongExtra(EXTRA_ID, -1), true)
            ACTION_DENY -> ApprovalRequests.resolve(intent.getLongExtra(EXTRA_ID, -1), false)
            ACTION_KILL -> GlobalKillSwitch.kill()
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.thatsimpletech.assist.APPROVE"
        const val ACTION_DENY = "com.thatsimpletech.assist.DENY"
        const val ACTION_KILL = "com.thatsimpletech.assist.KILL"
        const val EXTRA_ID = "request_id"
    }
}
