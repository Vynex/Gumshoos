package com.vynex.gumshoos.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

/**
 * Draws the search bar's background.
 *
 * A bitmap rather than a shape drawable because RemoteViews exposes no elevation, no outline
 * provider and no way to set a Drawable — the only remotable route to a drop shadow is
 * [Paint.setShadowLayer] on a software canvas. Tinting a `GradientDrawable` background could manage
 * fill, radius and wireframe, but one ColorStateList covers both solid and stroke, so a bordered
 * filled bar is impossible, and shadow is out of reach entirely.
 *
 * The settings preview calls [toBitmap] as well, so the two cannot drift: the preview is not an
 * imitation of the widget's background, it is the same pixels.
 */
object WidgetBarRenderer {

    /**
     * Shadow geometry, in dp.
     *
     * Tight blur and a short drop: a sharp edge reads as the bar sitting just above the wallpaper,
     * where a wide soft blur reads as haze and muddies whatever is behind it.
     */
    private const val SHADOW_BLUR_DP = 2.5f
    private const val SHADOW_DY_DP = 1.5f
    private const val SHADOW_ALPHA = 0x3A

    /**
     * Border width, in dp.
     *
     * Outline mode gets a heavier one. On a filled bar the border is the fill colour and therefore
     * invisible, so a hairline costs nothing there — but in outline mode it is the entire widget,
     * and 1dp reads as a scratch rather than as a deliberate edge.
     */
    private const val STROKE_DP = 1f
    private const val OUTLINE_STROKE_DP = 3f

    /**
     * The bar's colour, with [WidgetBarFill.Adaptive] resolved against the current theme.
     *
     * Adaptive reads the wallpaper-derived neutral palette, which is what makes one stored value
     * correct in both light and dark. It is resolved here, at draw time, rather than when the user
     * picks it — picking in daylight would otherwise freeze a light bar into the dark theme.
     */
    @ColorInt
    fun resolveFill(context: Context, fill: WidgetBarFill): Int = when (fill) {
        is WidgetBarFill.Solid -> fill.color
        WidgetBarFill.Adaptive -> {
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val resource = if (night) {
                android.R.color.system_neutral1_800
            } else {
                android.R.color.system_neutral1_50
            }
            ContextCompat.getColor(context, resource)
        }
    }

    private fun strokeWidthPx(style: WidgetBarStyle, density: Float): Float =
        (if (style.wireframe) OUTLINE_STROKE_DP else STROKE_DP) * density

    /**
     * Rasterises the background at [wPx] x [hPx].
     *
     * Software canvas, which is not incidental: [Paint.setShadowLayer] is documented as text-only on
     * a hardware-accelerated canvas, so a shadow drawn through Compose's `nativeCanvas` would
     * silently do nothing while this one worked.
     */
    fun toBitmap(
        context: Context,
        style: WidgetBarStyle,
        wPx: Int,
        hPx: Int,
        density: Float,
    ): Bitmap {
        val width = wPx.coerceAtLeast(1)
        val height = hPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(
            canvas = Canvas(bitmap),
            resolvedRgb = resolveFill(context, style.fill),
            style = style,
            width = width.toFloat(),
            height = height.toFloat(),
            density = density,
        )
        return bitmap
    }

    /**
     * The one drawing implementation.
     *
     * The bar's rectangle comes from the configured padding and **nothing else**. The shadow is
     * drawn into whatever margin that padding leaves, and is clipped by the bitmap edge if there
     * isn't any — so switching the shadow on never changes the bar's size. An earlier version inset
     * the rect to make room for the blur, which meant the shadow toggle silently doubled as a
     * height control and neither could be set without disturbing the other.
     */
    fun draw(
        canvas: Canvas,
        @ColorInt resolvedRgb: Int,
        style: WidgetBarStyle,
        width: Float,
        height: Float,
        density: Float,
    ) {
        val blur = SHADOW_BLUR_DP * density
        val dy = SHADOW_DY_DP * density
        val padH = style.paddingHDp * density
        val padV = style.paddingVDp * density

        val rect = RectF(padH, padV, width - padH, height - padV)
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val radius = WidgetBarMetrics.cornerRadiusPx(style.cornerPercent, rect.height())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val fill = WidgetBarMetrics.fillColor(resolvedRgb, style)
        val strokeWidth = strokeWidthPx(style, density)
        val stroke = WidgetBarMetrics.strokeColor(resolvedRgb, style)

        // The shadow is drawn from the shape itself, not carried by whatever else happens to be
        // painted, so it survives a fill and border that are both fully transparent.
        //
        // Skia derives a shadow from the alpha actually drawn — a transparent paint casts nothing —
        // so the shape goes down opaque to produce one, then is cleared straight back out. What
        // remains is the part of the shadow that fell outside the shape, which is all a drop shadow
        // ever shows: the rest would sit under the fill.
        //
        // This used to ride on the fill, and on the border in outline mode, which meant taking
        // opacity to zero silently took the shadow with it.
        if (style.shadow) {
            paint.style = Paint.Style.FILL
            paint.color = WidgetBarMetrics.argb(0xFF, 0, 0, 0)
            paint.setShadowLayer(blur, 0f, dy, WidgetBarMetrics.argb(SHADOW_ALPHA, 0, 0, 0))
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.clearShadowLayer()

            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.xfermode = null
        }

        if (WidgetBarMetrics.alphaOf(fill) > 0) {
            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        if (WidgetBarMetrics.alphaOf(stroke) > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = stroke
            // Inset by half the stroke: Canvas centres a stroke on the path, so the outer half
            // would otherwise fall outside the rect and be clipped to a thinner, harder edge.
            val half = strokeWidth / 2f
            val strokeRect = RectF(
                rect.left + half, rect.top + half, rect.right - half, rect.bottom - half,
            )
            val strokeRadius = (radius - half).coerceAtLeast(0f)
            canvas.drawRoundRect(strokeRect, strokeRadius, strokeRadius, paint)
        }
    }
}
