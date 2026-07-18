package com.vynex.gumshoos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-warms the window after a reboot so the first tap of the day isn't the slow one. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startForegroundService(
            OverlayService.intent(context, OverlayService.ACTION_WARM)
        )
    }
}
