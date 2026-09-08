package com.thatsimpletech.assist.kill

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to stop everything. Shows the kill state; tapping while killed re-arms. */
class KillSwitchTile : TileService() {
    private val listener: (Boolean) -> Unit = { render() }

    override fun onStartListening() {
        GlobalKillSwitch.addListener(listener)
        render()
    }

    override fun onStopListening() {
        GlobalKillSwitch.removeListener(listener)
    }

    override fun onClick() {
        if (GlobalKillSwitch.killed) GlobalKillSwitch.reset() else GlobalKillSwitch.kill()
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        tile.state = if (GlobalKillSwitch.killed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Stop Assist"
        tile.subtitle = if (GlobalKillSwitch.killed) "Stopped" else "Running"
        tile.updateTile()
    }
}
