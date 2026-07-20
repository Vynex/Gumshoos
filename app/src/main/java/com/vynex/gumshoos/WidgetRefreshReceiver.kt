package com.vynex.gumshoos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-pushes every placed widget after the app is upgraded.
 *
 * Without this, a layout change breaks widgets that are already on the home screen, and only those
 * — a freshly added one works, which makes it look like anything but what it is.
 *
 * The launcher caches our RemoteViews and its recorded actions reference views by **numeric** id.
 * Those numbers are assigned at build time in alphabetical order, so adding an id shifts every id
 * that sorts after it: `widget_root` moved when the search bar gained a background, a logo and
 * three action slots. The launcher then applies a cached `setOnClickPendingIntent` for the old
 * number against a freshly inflated layout, finds no such view, and the widget is left with no tap
 * target — at which point the launcher does whatever it does with an inert widget, which on some
 * skins is to open the app instead.
 *
 * MY_PACKAGE_REPLACED is one of the few broadcasts still allowed a manifest-declared receiver,
 * precisely because it is addressed to us rather than broadcast at everyone.
 *
 * Deliberately does not warm the service. That would be a background foreground-service start,
 * which Android 12+ refuses — the same restriction that keeps an onEnabled hook off the widget
 * provider. Pushing RemoteViews needs no service.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        EventLog.record("package replaced; refreshing widgets")
        refreshAllWidgets(context)
    }
}

/**
 * Rebuilds both widgets from the current code and config.
 *
 * Shared with the manual button in Advanced, so recovering a stale widget never depends on
 * reinstalling — the failure it exists for leaves the widget unable to open anything.
 */
fun refreshAllWidgets(context: Context) {
    runCatching { SearchWidgetProvider.refreshAll(context) }
        .onFailure { EventLog.record("bar widget refresh FAILED ${it.javaClass.simpleName}") }
    runCatching { SearchWidgetSmallProvider.refreshAll(context) }
        .onFailure { EventLog.record("app icon widget refresh FAILED ${it.javaClass.simpleName}") }
}
