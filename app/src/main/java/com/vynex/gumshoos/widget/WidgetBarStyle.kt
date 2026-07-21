package com.vynex.gumshoos.widget

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.vynex.gumshoos.R

/**
 * How the wide search bar is drawn.
 *
 * Deliberately free of Android graphics types. Everything here is data plus arithmetic, so the
 * geometry and colour rules are unit-testable — [WidgetBarRenderer] is the only part that needs a
 * Canvas, and it is kept thin for exactly that reason.
 */
data class WidgetBarStyle(
    val fill: WidgetBarFill,
    /** 0..100. Kept apart from the colour so changing one doesn't reset the other. */
    val fillAlphaPercent: Int,
    /** Border only, no fill. The stroke takes the fill colour. */
    val wireframe: Boolean,
    /** 0 = square, 100 = pill. A fraction of the bar's height, not a dp — see [WidgetBarMetrics]. */
    val cornerPercent: Int,
    val shadow: Boolean,
    val showHint: Boolean,
    val logo: WidgetBarLogo,
    /**
     * Gap between the bar and the edge of the widget's cell, in dp.
     *
     * What the user asked for; what is actually left is [WidgetBarMetrics.insetHDp], which floors
     * these at the room a [shadow] needs to land inside the widget rather than be cut off at its
     * edge.
     */
    val paddingHDp: Int = 0,
    val paddingVDp: Int = 0,
)

/**
 * Where the bar's colour comes from.
 *
 * [Adaptive] exists because the bar it ships imitating flips with the system theme, and a single
 * stored colour cannot. It resolves at draw time rather than at pick time, so one stored value is
 * right in both themes.
 */
sealed interface WidgetBarFill {
    data object Adaptive : WidgetBarFill
    data class Solid(@ColorInt val color: Int) : WidgetBarFill

    companion object {
        const val ADAPTIVE_ID = "ADAPTIVE"
        const val SOLID_ID = "SOLID"
    }
}

/**
 * The mark at the left of the bar.
 *
 * [tintable] is what stops a multicolour brand mark being flattened to one tone by the contrast
 * rule — the Google G has to keep its own colours whatever the bar is filled with.
 */
enum class WidgetBarLogo(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
    val tintable: Boolean,
) {
    MAGNIFIER("magnifier", "Magnifying glass", R.drawable.ic_provider_search, tintable = true),
    GOOGLE("google", "Google", R.drawable.ic_provider_google, tintable = false),
    ;

    companion object {
        fun fromId(id: String?): WidgetBarLogo? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The geometry and colour arithmetic behind the bar.
 *
 * Uses hand-rolled ARGB packing rather than `android.graphics.Color`: its static methods are
 * unmocked in unit tests and throw, which would put every rule in here beyond reach of a test.
 */
object WidgetBarMetrics {

    /** Below this, the fill is see-through enough that the wallpaper decides contrast, not us. */
    private const val OPAQUE_ENOUGH_PERCENT = 50

    /**
     * Bounds for the padding sliders, in dp.
     *
     * The vertical one is the tighter of the two because it fights the bar's own contents: the
     * margin comes out of the bar's height, and past roughly this much the 22dp logo and action
     * icons are taller than the bar they sit in on a single-row cell.
     */
    const val MAX_PADDING_H_DP = 32
    const val MAX_PADDING_V_DP = 12

    /**
     * The drop shadow, transcribed from a CSS box-shadow:
     *
     *     rgba(0, 0, 0, 0.15) 1.95px 1.95px 2.6px
     *
     * The offsets and the alpha carry across directly — CSS px is read as dp, which is what the
     * value means on a phone. The blur does not: CSS defines blur-radius as twice the Gaussian
     * sigma, and Skia wants a mask radius in pixels, which is [WidgetBarRenderer]'s business.
     *
     * They live here rather than with the drawing because the bar's geometry depends on them. How
     * much room a shadow needs is arithmetic, and arithmetic can be tested.
     */
    const val SHADOW_CSS_BLUR_DP = 2.6f
    const val SHADOW_DX_DP = 1.95f
    const val SHADOW_DY_DP = 1.95f

    /** 0.15 alpha, as eight-bit. */
    const val SHADOW_ALPHA = 0x26

    /** Sigma, in dp. */
    private const val SHADOW_SIGMA_DP = SHADOW_CSS_BLUR_DP / 2f

    /**
     * How far the shadow reaches past the bar, in dp, on each axis.
     *
     * A Gaussian never quite ends; Skia's mask stops carrying anything visible at about three sigma,
     * which is where the reach is taken from. Symmetric per axis even though the offset is not — an
     * inset of `reach - dy` at the top against `reach + dy` at the bottom would hold the shadow just
     * as well and leave the bar visibly off-centre in its cell.
     */
    const val SHADOW_EXTENT_H_DP = 3f * SHADOW_SIGMA_DP + SHADOW_DX_DP
    const val SHADOW_EXTENT_V_DP = 3f * SHADOW_SIGMA_DP + SHADOW_DY_DP

    /**
     * The gap actually left between the widget's cell and the bar, in dp.
     *
     * The configured margin, floored at what the shadow needs when there is one. The shadow used to
     * draw into whatever margin happened to be there and be sliced off square by the bitmap's edge
     * where there wasn't enough — which reads as a torn bar rather than as a shadow, and at a zero
     * margin is most of it.
     *
     * The floor rather than a sum: a margin already wider than the shadow needs nothing added, so
     * anything past [SHADOW_EXTENT_H_DP] sets the bar's size on its own and the shadow toggle stops
     * moving anything at all. The cost is that at a small margin the toggle *does* change the bar's
     * size, which is the honest trade — the alternative is a shadow that is drawn and then cut off.
     */
    fun insetHDp(style: WidgetBarStyle): Float =
        insetDp(style.paddingHDp, style.shadow, SHADOW_EXTENT_H_DP)

    fun insetVDp(style: WidgetBarStyle): Float =
        insetDp(style.paddingVDp, style.shadow, SHADOW_EXTENT_V_DP)

    private fun insetDp(paddingDp: Int, shadow: Boolean, extentDp: Float): Float {
        val padding = paddingDp.coerceAtLeast(0).toFloat()
        return if (shadow) maxOf(padding, extentDp) else padding
    }

    /** Widest and tallest we will ever rasterise, in dp. */
    const val MAX_WIDTH_DP = 1200
    const val MAX_HEIGHT_DP = 200

    /**
     * Ceiling on the rasterised area, in pixels.
     *
     * The bitmap crosses to the widget host, which holds a per-widget memory budget of roughly six
     * screens' worth. At four bytes a pixel this cap is ~2.4MB, against realistic bars that come out
     * between 0.7MB (a 4-cell bar at xxhdpi) and 1.9MB (full width on a tablet). Generous enough
     * never to bite in practice, low enough that a host reporting nonsense can't blow the budget.
     */
    const val MAX_AREA_PX = 600_000

    @ColorInt
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)

    fun alphaOf(@ColorInt color: Int): Int = (color ushr 24) and 0xFF
    fun redOf(@ColorInt color: Int): Int = (color ushr 16) and 0xFF
    fun greenOf(@ColorInt color: Int): Int = (color ushr 8) and 0xFF
    fun blueOf(@ColorInt color: Int): Int = color and 0xFF

    /**
     * The corner radius, as pixels, for a bar of this height.
     *
     * A fraction of the height rather than a stored dp, because the bar is resizable: 28dp is a pill
     * at 56dp tall and a rounded square at 100dp. Capped at half the height, so the shape can reach
     * a pill and never overshoot into a lens.
     */
    fun cornerRadiusPx(cornerPercent: Int, heightPx: Float): Float =
        (heightPx / 2f) * (cornerPercent.coerceIn(0, 100) / 100f)

    /**
     * [rgb] at [alphaPercent].
     *
     * Any alpha already in [rgb] is replaced, not multiplied — the picker hands back opaque swatches
     * and the opacity slider is the only thing that decides transparency.
     */
    @ColorInt
    fun applyAlpha(@ColorInt rgb: Int, alphaPercent: Int): Int =
        argb(
            alpha = Math.round(255f * alphaPercent.coerceIn(0, 100) / 100f),
            red = redOf(rgb),
            green = greenOf(rgb),
            blue = blueOf(rgb),
        )

    /** The fill actually painted. Transparent in wireframe mode — the stroke carries the colour. */
    @ColorInt
    fun fillColor(@ColorInt resolvedRgb: Int, style: WidgetBarStyle): Int =
        if (style.wireframe) 0 else applyAlpha(resolvedRgb, style.fillAlphaPercent)

    /**
     * The border. Wireframe mode only — a filled bar has none.
     *
     * A filled bar used to draw one too, in the fill colour, on the theory that a border the same
     * colour as the fill is invisible. It isn't: the stroke is painted *over* the fill, so along the
     * whole rounded edge two antialiased coverages composite one on top of the other, and every
     * opacity below 100% shows that seam as a darker rim. It was most of the way to invisible at
     * full opacity, which is where it was looked at.
     */
    @ColorInt
    fun strokeColor(@ColorInt resolvedRgb: Int, style: WidgetBarStyle): Int =
        if (style.wireframe) applyAlpha(resolvedRgb, style.fillAlphaPercent) else 0

    /**
     * Colour for the hint text and any tintable icon.
     *
     * Derived rather than configurable, so no combination of settings can produce white on white.
     * A see-through or wireframe bar sits over a wallpaper we know nothing about, so it falls back
     * to light content — which is what reads on the majority of home screens.
     */
    @ColorInt
    fun contentColor(@ColorInt resolvedRgb: Int, style: WidgetBarStyle): Int {
        val overWallpaper = style.wireframe || style.fillAlphaPercent < OPAQUE_ENOUGH_PERCENT
        if (overWallpaper) return argb(0xFF, 0xFF, 0xFF, 0xFF)
        return if (isLight(resolvedRgb)) {
            argb(0xFF, 0x1B, 0x1B, 0x1B)
        } else {
            argb(0xFF, 0xFF, 0xFF, 0xFF)
        }
    }

    /** Perceived brightness, weighted for how the eye actually responds to each channel. */
    fun luminance(@ColorInt color: Int): Float =
        (0.299f * redOf(color) + 0.587f * greenOf(color) + 0.114f * blueOf(color)) / 255f

    fun isLight(@ColorInt color: Int): Boolean = luminance(color) > 0.5f

    /**
     * How far a [wPx] x [hPx] drawing has to shrink to fit [MAX_AREA_PX]. 1 when it already does.
     *
     * For the settings preview, which knows its size in pixels directly and wants them 1:1 — the
     * widget goes through [renderSizePx] instead, because a launcher reports dp. Anything drawn at
     * this scale must be handed a density scaled by it too, or the margins and the shadow would be
     * drawn for a bitmap of a different size.
     */
    fun rasterScale(wPx: Int, hPx: Int): Float {
        val area = wPx.toLong() * hPx.toLong()
        if (area <= MAX_AREA_PX) return 1f
        return Math.sqrt(MAX_AREA_PX.toDouble() / area.toDouble()).toFloat()
    }

    /**
     * Pixel size to rasterise at, from the size the launcher reports.
     *
     * Every dimension is floored at one pixel: hosts report zero before the first layout pass, and
     * `Bitmap.createBitmap(0, ...)` throws — inside the widget update, which would take the host
     * down rather than just look wrong. Past [MAX_AREA_PX] both dimensions scale down together, so
     * the aspect ratio survives and `fitXY` stays a uniform scale rather than a stretch.
     */
    fun renderSizePx(widthDp: Int, heightDp: Int, density: Float): Pair<Int, Int> {
        val safeDensity = if (density > 0f) density else 1f
        val wDp = widthDp.coerceIn(1, MAX_WIDTH_DP)
        val hDp = heightDp.coerceIn(1, MAX_HEIGHT_DP)

        var w = Math.round(wDp * safeDensity).coerceAtLeast(1)
        var h = Math.round(hDp * safeDensity).coerceAtLeast(1)

        val area = w.toLong() * h.toLong()
        if (area > MAX_AREA_PX) {
            val scale = Math.sqrt(MAX_AREA_PX.toDouble() / area.toDouble()).toFloat()
            w = Math.round(w * scale).coerceAtLeast(1)
            h = Math.round(h * scale).coerceAtLeast(1)
        }
        return w to h
    }
}
