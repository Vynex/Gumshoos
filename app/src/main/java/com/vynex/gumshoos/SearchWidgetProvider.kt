package com.vynex.gumshoos

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.vynex.gumshoos.widget.WidgetAction
import com.vynex.gumshoos.widget.WidgetBarMetrics
import com.vynex.gumshoos.widget.WidgetBarRenderer
import kotlin.math.roundToInt

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
    protected open fun decorate(
        context: Context,
        views: RemoteViews,
        manager: AppWidgetManager,
        appWidgetId: Int,
    ) = Unit

    /**
     * One RemoteViews per widget, not one for all of them.
     *
     * The bar is resizable, so two placed copies can be different widths, and its background is
     * rasterised to match. A single shared bitmap would be stretched to fit both and show visibly
     * elliptical corners on the wider one.
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, appWidgetManager, id))
        }
    }

    /**
     * Resize hook. Without it a bar keeps whatever width it was first drawn at, and the background
     * is stretched to the new size instead of redrawn for it.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetManager, appWidgetId))
    }

    // Deliberately no onEnabled hook. Starting a foreground service from a broadcast receiver is a
    // background FGS start, which Android 12+ blocks with ForegroundServiceStartNotAllowedException.
    // A widget *click* is exempt; widget *placement* is not. Warming happens from the app instead.

    /**
     * The tap target is wired before styling, and styling is allowed to fail.
     *
     * A throw inside [decorate] would otherwise leave the host with no RemoteViews at all — a
     * widget that reads as broken rather than as unstyled. This way the worst case is the static
     * layout, which still opens search, and the reason lands in the event trace.
     *
     * Note this cannot catch an *inflation* failure: that happens in the launcher's process, from
     * the layout XML, and is invisible here. See the note in widget_search_bar.xml.
     */
    internal fun buildViews(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
    ): RemoteViews =
        RemoteViews(context.packageName, layoutRes).apply {
            setOnClickPendingIntent(R.id.widget_root, showPendingIntent(context))
            runCatching { decorate(context, this, manager, appWidgetId) }
                .onFailure { EventLog.record("widget decorate FAILED $sourceTag ${it.javaClass.simpleName}: ${it.message}") }
        }

    private fun showPendingIntent(context: Context): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            requestCode,
            OverlayService.intent(context, OverlayService.ACTION_SHOW, source = sourceTag),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/**
 * Re-pushes every placed widget of one provider, so config changes show up without re-adding it.
 *
 * Free function rather than an inherited companion — companions aren't inheritable, which is why
 * the app icon widget previously had this and the bar had nothing at all.
 */
/**
 * Rasterises a drawable so it can be handed to a widget as a bitmap rather than a resource id.
 *
 * The whole reason the icons go into the RemoteViews as bitmaps: `setImageViewResource` sends the
 * launcher a bare `R.drawable` integer, and those integers are renumbered alphabetically every time
 * a drawable is added. The launcher caches the widget's RemoteViews and re-resolves that integer
 * against *its own* copy of our resources, which after an update is stale — so an id that meant
 * "Google" now lands on whatever drawable took that slot, and no re-push of ids can heal it until
 * the launcher process reloads our resources. A bitmap carries the pixels inside the RemoteViews;
 * there is no id to go stale. The bar background already sits in the RemoteViews this way.
 *
 * Tint still rides as a separate `setColorStateList` action on the ImageView, so a tintable mark is
 * recoloured from the bitmap's alpha exactly as it was when the source was a resource.
 */
private fun rasterizeIcon(context: Context, @DrawableRes resId: Int, sizePx: Int): Bitmap {
    val size = sizePx.coerceAtLeast(1)
    val drawable = requireNotNull(ContextCompat.getDrawable(context, resId)) {
        "missing drawable $resId"
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    // Fit the drawable's own aspect ratio, centred, rather than forcing it into the square — Voice
    // Search is a tall 300×429 mark and stretches wide otherwise. This reproduces what the ImageView
    // did for a resource: its default scaleType is fitCenter.
    val iw = drawable.intrinsicWidth
    val ih = drawable.intrinsicHeight
    if (iw <= 0 || ih <= 0) {
        drawable.setBounds(0, 0, size, size)
    } else {
        val scale = minOf(size.toFloat() / iw, size.toFloat() / ih)
        val w = (iw * scale).roundToInt()
        val h = (ih * scale).roundToInt()
        val left = (size - w) / 2
        val top = (size - h) / 2
        drawable.setBounds(left, top, left + w, top + h)
    }
    drawable.draw(Canvas(bitmap))
    return bitmap
}

private fun refreshProvider(context: Context, provider: SearchWidgetProviderBase, type: Class<*>) {
    val manager = AppWidgetManager.getInstance(context) ?: return
    val ids = manager.getAppWidgetIds(ComponentName(context, type))
    if (ids.isEmpty()) return
    ids.forEach { id -> manager.updateAppWidget(id, provider.buildViews(context, manager, id)) }
}

/** Full-width search bar, closest to the Nova home screen widget. */
class SearchWidgetProvider : SearchWidgetProviderBase() {
    override val layoutRes = R.layout.widget_search_bar
    override val requestCode = 100
    override val sourceTag = "widget-bar"

    /**
     * Applies the configured style.
     *
     * The background is a bitmap because RemoteViews cannot set a Drawable and a shape drawable
     * cannot cast a shadow — see [WidgetBarRenderer]. Everything else is plain RemoteViews calls
     * against the ids the layout declares.
     */
    override fun decorate(
        context: Context,
        views: RemoteViews,
        manager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        val style = GumshoosConfig.widgetBarStyle
        val density = context.resources.displayMetrics.density
        val (widthDp, heightDp) = measuredSizeDp(context, manager, appWidgetId)
        val (wPx, hPx) = WidgetBarMetrics.renderSizePx(widthDp, heightDp, density)

        views.setImageViewBitmap(
            R.id.widget_background,
            WidgetBarRenderer.toBitmap(context, style, wPx, hPx, density),
        )

        val resolved = WidgetBarRenderer.resolveFill(context, style.fill)
        val content = WidgetBarMetrics.contentColor(resolved, style)

        // The content sits inside the bar, so it has to move with the bar's own margin — otherwise
        // dropping the horizontal margin to zero leaves the logo hanging off the left edge. Read
        // through insetHDp rather than off the style, because a shadow widens that margin and the
        // logo has to follow the edge it actually produced.
        val edge = ((WidgetBarMetrics.insetHDp(style) + CONTENT_INSET_DP) * density).toInt()
        views.setViewPadding(R.id.widget_content, edge, 0, edge, 0)

        val logoPx = context.resources.getDimensionPixelSize(R.dimen.widget_bar_logo_size)
        views.setImageViewBitmap(R.id.widget_logo, rasterizeIcon(context, style.logo.icon, logoPx))
        // Null, not a colour, for a brand mark: a tint would flatten it to one tone.
        views.setColorStateList(
            R.id.widget_logo,
            "setImageTintList",
            if (style.logo.tintable) ColorStateList.valueOf(content) else null,
        )

        val actionPx = context.resources.getDimensionPixelSize(R.dimen.widget_bar_action_size)

        views.setViewVisibility(R.id.widget_hint, if (style.showHint) View.VISIBLE else View.GONE)
        views.setTextColor(R.id.widget_hint, content)

        // Availability is checked at render, not stored. An action whose app is missing is skipped
        // here and keeps its saved on/off state, so installing the app puts the icon back in the
        // slot it had rather than requiring the bar to be set up again. Skipping also closes the
        // gap: the slots pack up, so a hidden Lens doesn't leave a hole in the middle of the bar.
        val enabled = SearchProviders.inConfiguredOrder(
            WidgetAction.entries.filter {
                it.id in GumshoosConfig.widgetBarActions &&
                    WidgetActionTargets.isAvailable(context, it)
            },
            GumshoosConfig.widgetBarActionOrder,
            WidgetAction::id,
        )
        WidgetAction.slots(enabled).forEachIndexed { index, action ->
            val slot = ACTION_SLOTS[index]
            if (action == null) {
                views.setViewVisibility(slot, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(slot, View.VISIBLE)
            views.setImageViewBitmap(slot, rasterizeIcon(context, action.icon, actionPx))
            views.setColorStateList(
                slot,
                "setImageTintList",
                if (action.tintable) ColorStateList.valueOf(content) else null,
            )
            views.setOnClickPendingIntent(slot, WidgetActionActivity.pendingIntent(context, action))
        }
    }

    /**
     * The size the launcher says this widget occupies, in dp.
     *
     * Prefers the exact size list the host provides, falling back to the min-width/max-height pair
     * and then to the size declared in the widget metadata. Hosts report zero before the first
     * layout pass, which is why [WidgetBarMetrics.renderSizePx] floors everything at a pixel.
     */
    private fun measuredSizeDp(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
    ): Pair<Int, Int> {
        val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }.getOrNull()
            ?: return DEFAULT_WIDTH_DP to DEFAULT_HEIGHT_DP

        @Suppress("DEPRECATION")
        val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        val largest = sizes?.maxByOrNull { it.width * it.height }
        if (largest != null && largest.width > 0f && largest.height > 0f) {
            return largest.width.toInt() to largest.height.toInt()
        }

        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return (if (width > 0) width else DEFAULT_WIDTH_DP) to
            (if (height > 0) height else DEFAULT_HEIGHT_DP)
    }

    companion object {
        /** Matches search_widget_info.xml, used until the host reports a real size. */
        private const val DEFAULT_WIDTH_DP = 250
        private const val DEFAULT_HEIGHT_DP = 56

        /** Gap from the bar's own edge to the logo. Mirrors @dimen/widget_bar_inset. */
        private const val CONTENT_INSET_DP = 18

        private val ACTION_SLOTS = intArrayOf(
            R.id.widget_action_1, R.id.widget_action_2, R.id.widget_action_3,
        )

        fun refreshAll(context: Context) {
            refreshProvider(context, SearchWidgetProvider(), SearchWidgetProvider::class.java)
        }
    }
}

/** Single cell, styled to pass for an app icon. */
class SearchWidgetSmallProvider : SearchWidgetProviderBase() {
    override val layoutRes = R.layout.widget_search_icon
    override val requestCode = 101
    override val sourceTag = "widget-app-icon"

    /**
     * Size and vertical position come from config rather than the layout, so they can be tuned from
     * the app without a rebuild — how an icon sits in its cell varies by launcher.
     *
     * The offset is applied as a margin pair because FrameLayout centres a child *within* its
     * margins: with gravity=center, a top margin of 2N shifts the child down by exactly N. Setting
     * one side to twice the offset and the other to zero therefore gives an exact nudge in either
     * direction, which a single margin would not.
     *
     * Ignores the manager and id: this widget is a fixed single cell, so its rendering doesn't
     * depend on the size the host reports.
     */
    override fun decorate(
        context: Context,
        views: RemoteViews,
        manager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        val size = GumshoosConfig.widgetIconSizeDp.toFloat()
        views.setViewLayoutWidth(R.id.widget_icon, size, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutHeight(R.id.widget_icon, size, TypedValue.COMPLEX_UNIT_DIP)

        // A bitmap rather than the layout's static src, for the same reason the bar's icons are —
        // a renumbered R.drawable resolves to the wrong glyph in the launcher's stale resource copy.
        val density = context.resources.displayMetrics.density
        val sizePx = (size * density).roundToInt()
        views.setImageViewBitmap(
            R.id.widget_icon,
            rasterizeIcon(context, R.drawable.ic_widget_search_app, sizePx),
        )

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
        /** Re-pushes every placed app icon widget so config changes show up without re-adding it. */
        fun refreshAll(context: Context) {
            refreshProvider(
                context, SearchWidgetSmallProvider(), SearchWidgetSmallProvider::class.java,
            )
        }
    }
}
