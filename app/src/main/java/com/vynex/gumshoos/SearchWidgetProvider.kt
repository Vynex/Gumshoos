package com.vynex.gumshoos

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * The fast invoke path.
 *
 * A tap here fires a PendingIntent straight into our already-running service — no activity launch,
 * no window creation, no launcher app-open transition. Plain RemoteViews rather than Glance: the
 * widget is a static tap target, and Glance would add a moving part to a latency experiment
 * without buying anything.
 */
class SearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_search_bar).apply {
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
            REQUEST_CODE,
            OverlayService.intent(context, OverlayService.ACTION_SHOW, source = "widget"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val REQUEST_CODE = 100
    }
}
