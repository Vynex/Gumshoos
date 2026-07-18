package com.vynex.gumshoos

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Second invoke path, for comparison against the widget. Also dodges the launcher's app-open
 * transition, but costs a shade pull-down first.
 */
class SearchTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Gumshoos"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        startForegroundService(
            OverlayService.intent(this, OverlayService.ACTION_SHOW, source = "tile")
        )
        // Collapse the shade, otherwise it stays over our window.
        @Suppress("DEPRECATION")
        startActivityAndCollapse(
            android.app.PendingIntent.getActivity(
                this,
                0,
                android.content.Intent(this, NoOpActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        )
    }
}
