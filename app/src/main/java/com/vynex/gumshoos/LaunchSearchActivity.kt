package com.vynex.gumshoos

import android.app.Activity
import android.os.Build
import android.os.Bundle

/**
 * Trampoline: starts the overlay and gets out of the way immediately.
 *
 * Exists because a launcher shortcut cannot start a service. ShortcutInfo only accepts an activity
 * intent, and there is no shortcut API that fires a service or broadcast — widgets are the only
 * home-screen target that can. So a shortcut has to go through an activity, and this is the
 * cheapest possible one: translucent, no window animation, no history, no task of its own, and
 * finished before it ever draws.
 *
 * It is still slower than the widget, which reaches the service directly. The overlay's latency
 * readout tags this path "icon" so the two can be compared rather than assumed.
 */
class LaunchSearchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(
            OverlayService.intent(this, OverlayService.ACTION_SHOW, source = "icon")
        )

        // Finish immediately: the overlay is a separate window and doesn't need us alive.
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
