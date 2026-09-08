package com.thatsimpletech.assist.intent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.thatsimpletech.assist.core.intent.Extra
import com.thatsimpletech.assist.core.intent.IntentSpec
import com.thatsimpletech.assist.core.loop.ExecResult

/**
 * Copies an [IntentSpec] onto `android.content.Intent` and starts it. Always
 * [Intent.FLAG_ACTIVITY_NEW_TASK]. Never invents URIs; never `ACTION_CALL` / SmsManager.
 */
class IntentExecutor(private val context: Context) {
    fun start(spec: IntentSpec): ExecResult {
        val intent = Intent(spec.action)
        val uri = spec.uri?.let { Uri.parse(it) }
        when {
            uri != null && spec.mimeType != null -> intent.setDataAndType(uri, spec.mimeType)
            uri != null -> intent.data = uri
            spec.mimeType != null -> intent.type = spec.mimeType
        }
        for ((key, extra) in spec.extras) {
            when (extra) {
                is Extra.Str -> intent.putExtra(key, extra.value)
                is Extra.IntNum -> intent.putExtra(key, extra.value)
                is Extra.Bool -> intent.putExtra(key, extra.value)
                is Extra.LongNum -> intent.putExtra(key, extra.value)
            }
        }
        spec.pkg?.let { intent.setPackage(it) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (spec.flags != 0) intent.addFlags(spec.flags)
        return try {
            context.startActivity(intent)
            ExecResult.OK
        } catch (_: ActivityNotFoundException) {
            ExecResult.error("no app to handle ${spec.action}")
        } catch (_: Exception) {
            ExecResult.error("no app to handle ${spec.action}")
        }
    }
}
