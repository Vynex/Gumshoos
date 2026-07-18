package com.vynex.gumshoos

import android.app.Activity
import android.os.Bundle

/**
 * Exists only so [SearchTileService] has something to hand `startActivityAndCollapse`, which
 * requires an activity PendingIntent but whose real purpose here is collapsing the shade.
 */
class NoOpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
}
