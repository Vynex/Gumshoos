package com.vynex.gumshoos

import android.app.Activity
import android.os.Build
import android.os.Bundle

/**
 * Exists only so [SearchTileService] has something to hand `startActivityAndCollapse`, which
 * requires an activity PendingIntent but whose real purpose here is collapsing the shade.
 */
class NoOpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
