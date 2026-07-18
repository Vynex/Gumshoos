package com.vynex.gumshoos

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
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
            label = getString(R.string.app_name)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        startForegroundService(
            OverlayService.intent(this, OverlayService.ACTION_SHOW, source = "tile")
        )
        collapseShade()
    }

    /** The shade sits above our overlay window, so it has to come down or nothing is visible. */
    // The Intent overload is deprecated but is the only option below API 34, which minSdk 31 allows.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun collapseShade() {
        val intent = Intent(this, NoOpActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
