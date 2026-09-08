package com.thatsimpletech.assist.config

import android.content.Context

/**
 * Runtime toggles that are not the brain. Auto mode skips *our* approve cards (task start
 * and Send/pay/delete). It cannot skip Android's own switches (Accessibility, overlay).
 */
object RunPrefs {
    private const val PREFS = "run"
    private const val AUTO = "auto"

    fun auto(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO, false)

    fun setAuto(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO, on).apply()
    }
}
