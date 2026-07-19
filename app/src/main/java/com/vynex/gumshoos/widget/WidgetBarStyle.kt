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
     * The bar's size depends on these and on nothing else — in particular not on [shadow], which
     * draws into whatever margin these leave rather than reserving any of its own.
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

    /** Bounds for the padding sliders, in dp. */
    const val MAX_PADDING_H_DP = 32
    const val MAX_PADDING_V_DP = 24

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
     * The border — always the fill colour, filled or not.
     *
     * It used to be a fixed white hairline on a filled bar, which showed as a pale rim against any
     * fill that wasn't near-white and read as a rendering fault rather than a border. Matching the
     * fill makes it vanish into a filled bar and carry the colour in wireframe mode, where it is
     * the only thing drawn, so one rule covers both.
     */
    @ColorInt
    fun strokeColor(@ColorInt resolvedRgb: Int, style: WidgetBarStyle): Int =
        applyAlpha(resolvedRgb, style.fillAlphaPercent)

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
