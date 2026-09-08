package com.thatsimpletech.assist.task

import android.content.Context

/** Last task outcome, so a failed run is still readable after the service stops. */
object LastRun {
    private const val PREFS = "last_run"

    fun save(ctx: Context, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("line", text).apply()
    }

    fun load(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("line", "") ?: ""
}
