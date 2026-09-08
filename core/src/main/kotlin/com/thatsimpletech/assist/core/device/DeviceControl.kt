package com.thatsimpletech.assist.core.device

import com.thatsimpletech.assist.core.grammar.Action
import com.thatsimpletech.assist.core.grammar.VolumeChange

/**
 * Direct-device payloads: flashlight, DND, brightness, volume.
 *
 * The [Action] subclasses from TM-016 *are* these payloads; this file
 * re-exports them so app executors do not invent a second type.
 * Construction clamps brightness and volume percent to 0..100.
 *
 * CameraManager / NotificationManager / Settings.System / AudioManager
 * adapters live in `app/` (Workstream E). This module stays SDK-less.
 */
object DeviceControl {
    const val PERCENT_MIN = 0
    const val PERCENT_MAX = 100

    /**
     * `AudioManager.FLAG_SHOW_UI`. Volume must always pass this bit so the
     * system slider is visible. Never call adjust/setStreamVolume with flags 0.
     */
    const val FLAG_SHOW_UI = 0x00000001

    fun torch(on: Boolean): Action.Torch = Action.Torch(on)

    fun dnd(on: Boolean): Action.Dnd = Action.Dnd(on)

    fun brightness(percent: Int): Action.Brightness =
        Action.Brightness(percent.coerceIn(PERCENT_MIN, PERCENT_MAX))

    fun volume(change: VolumeChange): Action.Volume = Action.Volume(clamp(change))

    fun clampPercent(percent: Int): Int = percent.coerceIn(PERCENT_MIN, PERCENT_MAX)

    private fun clamp(change: VolumeChange): VolumeChange = when (change) {
        VolumeChange.Up, VolumeChange.Down -> change
        is VolumeChange.Percent -> VolumeChange.Percent(clampPercent(change.n))
    }
}

typealias Torch = Action.Torch
typealias Dnd = Action.Dnd
typealias Brightness = Action.Brightness
typealias Volume = Action.Volume
