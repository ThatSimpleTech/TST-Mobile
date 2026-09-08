package com.thatsimpletech.assist.kill

import com.thatsimpletech.assist.core.loop.KillSwitch

/**
 * One flag, three ways to set it: the Quick Settings tile, the Stop action on the task
 * notification, and the app. The loop polls it before every step and before every execution.
 * Resetting it is a deliberate act in the app, never automatic.
 */
object GlobalKillSwitch : KillSwitch {
    @Volatile
    override var killed: Boolean = false
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun kill() {
        killed = true
        listeners.forEach { it(true) }
    }

    fun reset() {
        killed = false
        listeners.forEach { it(false) }
    }

    fun addListener(l: (Boolean) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Boolean) -> Unit) {
        listeners.remove(l)
    }
}
