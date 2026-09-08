package com.thatsimpletech.assist.intent

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.thatsimpletech.assist.core.loop.ExecResult

/**
 * Runtime / special grants the executor consults after policy, before the API.
 * A denied OS grant is [ExecResult.error], not a policy REFUSE.
 *
 * Dial-only / SMS-draft (Q2, Q3): this class does **not** request `CALL_PHONE`
 * or `SEND_SMS`. Those verbs never need them.
 */
class PermissionGate(private val context: Context) {
    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    fun dndAccessGranted(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    fun cameraGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun missingWriteSettings(): ExecResult? =
        if (canWriteSettings()) null
        else ExecResult.error("write settings is off; grant it in Settings.ACTION_MANAGE_WRITE_SETTINGS")

    fun missingDnd(): ExecResult? =
        if (dndAccessGranted()) null
        else ExecResult.error("Do Not Disturb access is off; grant it in Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS")

    fun missingCamera(): ExecResult? =
        if (cameraGranted()) null
        else ExecResult.error("camera permission is off; flashlight needs CAMERA")
}
