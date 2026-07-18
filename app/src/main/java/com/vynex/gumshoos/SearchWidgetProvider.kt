package com.vynex.gumshoos

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.util.TypedValue
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

    /** Hook for size/position overrides applied on top of the static layout. */
    protected open fun decorate(context: Context, views: RemoteViews) = Unit

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetManager.updateAppWidget(appWidgetIds, buildViews(context))
    }

    // Deliberately no onEnabled hook. Starting a foreground service from a broadcast receiver is a
    // background FGS start, which Android 12+ blocks with ForegroundServiceStartNotAllowedException.
    // A widget *click* is exempt; widget *placement* is not. Warming happens from the app instead.

    protected fun buildViews(context: Context): RemoteViews =
        RemoteViews(context.packageName, layoutRes).apply {
            setOnClickPendingIntent(R.id.widget_root, showPendingIntent(context))
            decorate(context, this)
        }

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

/** Single cell, styled to pass for an app icon. */
class SearchWidgetSmallProvider : SearchWidgetProviderBase() {
    override val layoutRes = R.layout.widget_search_icon
    override val requestCode = 101
    override val sourceTag = "widget-1x1"

    /**
     * Size and vertical position come from config rather than the layout, so they can be tuned from
     * the app without a rebuild — how an icon sits in its cell varies by launcher.
     *
     * The offset is applied as a margin pair because FrameLayout centres a child *within* its
     * margins: with gravity=center, a top margin of 2N shifts the child down by exactly N. Setting
     * one side to twice the offset and the other to zero therefore gives an exact nudge in either
     * direction, which a single margin would not.
     */
    override fun decorate(context: Context, views: RemoteViews) {
        val size = GumshoosConfig.widgetIconSizeDp.toFloat()
        views.setViewLayoutWidth(R.id.widget_icon, size, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutHeight(R.id.widget_icon, size, TypedValue.COMPLEX_UNIT_DIP)

        val offset = GumshoosConfig.widgetIconOffsetDp
        val top = if (offset > 0) offset * 2f else 0f
        val bottom = if (offset < 0) -offset * 2f else 0f
        views.setViewLayoutMargin(
            R.id.widget_icon, RemoteViews.MARGIN_TOP, top, TypedValue.COMPLEX_UNIT_DIP,
        )
        views.setViewLayoutMargin(
            R.id.widget_icon, RemoteViews.MARGIN_BOTTOM, bottom, TypedValue.COMPLEX_UNIT_DIP,
        )
    }

    companion object {
        /** Re-pushes every placed 1x1 widget so config changes show up without re-adding it. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, SearchWidgetSmallProvider::class.java)
            )
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, SearchWidgetSmallProvider().buildViews(context))
        }
    }
}
