package com.thatsimpletech.assist.task

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import com.thatsimpletech.assist.Graph
import com.thatsimpletech.assist.config.RunPrefs
import com.thatsimpletech.assist.kill.GlobalKillSwitch

/** Start a spoken or typed goal without opening the settings screen. */
object GoalStart {
    fun run(context: Context, goal: String): String {
        val g = goal.trim()
        if (g.isEmpty()) return "no goal"
        if (GlobalKillSwitch.killed) return "stopped: kill switch is set; re-arm it in the app"
        if (locked(context)) {
            if (RunPrefs.speak(context)) Graph.voice.speak("unlock the phone first")
            return "unlock the phone to run"
        }
        return try {
            context.startForegroundService(
                Intent(context, TaskForegroundService::class.java)
                    .putExtra(TaskForegroundService.EXTRA_GOAL, g),
            )
            LastRun.save(context, "started: $g")
            "started: $g — watch the EZER notification"
        } catch (e: Exception) {
            val m = "could not start: ${e.message}"
            LastRun.save(context, m)
            m
        }
    }

    fun locked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
}
