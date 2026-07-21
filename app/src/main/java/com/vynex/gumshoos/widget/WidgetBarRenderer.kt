package com.vynex.gumshoos.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
     * Skia's radius-to-sigma constant, inverted.
     *
     * The shadow's own numbers — blur, offsets, alpha, and the room it needs — are in
     * [WidgetBarMetrics], because the bar's geometry depends on them and can be tested there. What
     * is left here is the part that needs a Paint.
     */
    private const val SIGMA_PER_RADIUS = 0.57735f
    private const val SIGMA_BASE = 0.5f

    /**
     * Border width, in dp.
     *
     * Only outline mode has a border at all — see [WidgetBarMetrics.strokeColor] — and there it is
     * the entire widget, so 1dp would read as a scratch rather than as a deliberate edge.
     */
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

    /**
     * The BlurMaskFilter radius that reproduces a CSS blur-radius of [cssBlurDp].
     *
     * Both conversions are in play: CSS blur is 2x sigma, and Skia's radius is sigma with its own
     * affine mapping. Done in pixels because Skia's constant is a pixel quantity — the same dp
     * value produces a different radius at a different density. At 3x, 2.6dp means sigma 3.9;
     * passing 2.6dp straight through as a radius would land near sigma 5.0 and give a noticeably
     * softer shadow than the one specified.
     */
    private fun blurRadiusPx(cssBlurDp: Float, density: Float): Float {
        val sigma = (cssBlurDp * density) / 2f
        return ((sigma - SIGMA_BASE) / SIGMA_PER_RADIUS).coerceAtLeast(0f)
    }

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
     * The bar's rectangle comes from [WidgetBarMetrics.insetHDp] and [WidgetBarMetrics.insetVDp] —
     * the configured margin, widened to hold the shadow where the margin alone would let the bitmap
     * edge cut it off.
     */
    fun draw(
        canvas: Canvas,
        @ColorInt resolvedRgb: Int,
        style: WidgetBarStyle,
        width: Float,
        height: Float,
        density: Float,
    ) {
        val blur = blurRadiusPx(WidgetBarMetrics.SHADOW_CSS_BLUR_DP, density)
        val dx = WidgetBarMetrics.SHADOW_DX_DP * density
        val dy = WidgetBarMetrics.SHADOW_DY_DP * density
        val insetH = WidgetBarMetrics.insetHDp(style) * density
        val insetV = WidgetBarMetrics.insetVDp(style) * density

        val rect = RectF(insetH, insetV, width - insetH, height - insetV)
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val radius = WidgetBarMetrics.cornerRadiusPx(style.cornerPercent, rect.height())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val fill = WidgetBarMetrics.fillColor(resolvedRgb, style)
        val strokeWidth = OUTLINE_STROKE_DP * density
        val stroke = WidgetBarMetrics.strokeColor(resolvedRgb, style)

        // The shadow is drawn from the shape itself, not carried by whatever else happens to be
        // painted, so it survives a fill and border that are both fully transparent. It used to
        // ride on the fill, and on the border in outline mode, which meant taking opacity to zero
        // silently took the shadow with it.
        //
        // Skia derives a shadow from the alpha actually drawn — a transparent paint casts nothing —
        // so the shape goes down opaque and the shape's own area is clipped away instead. What
        // remains is the part of the blur that fell outside the shape, which is all a drop shadow
        // ever shows: the rest would sit under the fill.
        //
        // The clip is why this isn't drawn and then erased with a CLEAR xfermode, which is what it
        // did before. Both passes were antialiased, so along the whole rounded edge the erase
        // removed only `coverage` of a pixel that held `coverage` of black, leaving `c * (1 - c)`
        // behind — a ring of dark speckle peaking where coverage was half, which is exactly the
        // hard, crawling edge you see at 0% opacity with nothing painted over it. Clipping applies
        // the same coverage as a multiply on the way in and leaves nothing to subtract.
        if (style.shadow) {
            val shape = Path().apply {
                addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipOutPath(shape)
            paint.style = Paint.Style.FILL
            paint.color = WidgetBarMetrics.argb(0xFF, 0, 0, 0)
            paint.setShadowLayer(
                blur, dx, dy, WidgetBarMetrics.argb(WidgetBarMetrics.SHADOW_ALPHA, 0, 0, 0),
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.clearShadowLayer()
            canvas.restore()
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
