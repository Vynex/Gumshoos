package com.vynex.gumshoos

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * The fast invoke path.
 *
 * A tap fires a PendingIntent straight into our already-running service — no activity launch, no
 * window creation, no launcher app-open transition. Plain RemoteViews rather than Glance: these are
 * static tap targets and Glance would add a moving part to the latency-critical path for nothing.
 */
abstract class SearchWidgetProviderBase : AppWidgetProvider() {

    protected abstract val layoutRes: Int

    /** Must be distinct per provider: PendingIntent equality ignores extras, so sharing a code
     *  would let one widget's intent overwrite the other's. */
    protected abstract val requestCode: Int

    /** Reported back in the latency log so the two widget sizes can be told apart. */
    protected abstract val sourceTag: String

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = RemoteViews(context.packageName, layoutRes).apply {
            setOnClickPendingIntent(R.id.widget_root, showPendingIntent(context))
        }
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    // Deliberately no onEnabled hook. Starting a foreground service from a broadcast receiver is a
    // background FGS start, which Android 12+ blocks with ForegroundServiceStartNotAllowedException.
    // A widget *click* is exempt; widget *placement* is not. Warming happens from the app instead.

    private fun showPendingIntent(context: Context): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            requestCode,
            OverlayService.intent(context, OverlayService.ACTION_SHOW, source = sourceTag),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** Full-width search bar, closest to the Nova home screen widget. */
class SearchWidgetProvider : SearchWidgetProviderBase() {
    override val layoutRes = R.layout.widget_search_bar
    override val requestCode = 100
    override val sourceTag = "widget-bar"
}

/** Single cell, for people who'd rather not give up a whole row to a search bar. */
class SearchWidgetSmallProvider : SearchWidgetProviderBase() {
    override val layoutRes = R.layout.widget_search_icon
    override val requestCode = 101
    override val sourceTag = "widget-1x1"
}
