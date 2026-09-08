package com.thatsimpletech.assist.partner

import android.app.appfunctions.AppFunctionManager
import android.content.Context
import android.os.Build
import com.thatsimpletech.assist.core.loop.ExecResult

/**
 * App Functions for partner apps (Workstream F). `AppFunctionManager` is API 36.
 * Unpublished or pre-36: [available] is false, never throws. Function ids stay
 * null until a device dump confirms them (Q10); the intent rung is M2 success.
 */
class AppFunctionExecutor(private val context: Context) {
    fun available(pkg: String, functionId: String): Boolean {
        if (functionId.isBlank()) return false
        if (Build.VERSION.SDK_INT < 36) return false
        manager() ?: return false
        // Q10: treat as unpublished until Adam pastes ids. A non-null id in
        // [PartnerFunctions] is still not a published claim without a dump.
        return false
    }

    suspend fun invoke(pkg: String, functionId: String): ExecResult {
        if (!available(pkg, functionId)) {
            return ExecResult.error("App Function is unpublished")
        }
        return ExecResult.error("App Function is unpublished")
    }

    private fun manager(): AppFunctionManager? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try {
            context.getSystemService(AppFunctionManager::class.java)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Fill only with ids confirmed from partner docs / a device dump.
 * Null means skip straight to intents.
 */
object PartnerFunctions {
    val WHATSAPP_SEND: String? = null
    val SPOTIFY_PLAY: String? = null
    val SPOTIFY_PAUSE: String? = null
    val SPOTIFY_NEXT: String? = null
    val SPOTIFY_PREV: String? = null
    val GMAIL_SEND: String? = null
}
