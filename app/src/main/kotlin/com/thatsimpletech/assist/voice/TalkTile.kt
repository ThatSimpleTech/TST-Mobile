package com.thatsimpletech.assist.voice

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.thatsimpletech.assist.ui.MainActivity

/** Quick Settings tile: tap to start on-device listen (TM-029). Same path as the Talk button. */
class TalkTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = "Talk"
        tile.subtitle = "Say a goal"
        tile.updateTile()
    }

    override fun onClick() {
        val pi = PendingIntent.getActivity(
            this,
            0,
            MainActivity.listenIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startActivityAndCollapse(pi)
    }
}
