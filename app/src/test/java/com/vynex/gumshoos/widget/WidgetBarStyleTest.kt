package com.vynex.gumshoos.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry and colour rules behind the search bar.
 *
 * The drawing itself needs a Canvas and is checked on device; everything decided *before* the
 * canvas is here, which is why [WidgetBarRenderer] is kept as thin as it is.
 */
class WidgetBarStyleTest {

    private fun style(
        fill: WidgetBarFill = WidgetBarFill.Solid(0xFF000000.toInt()),
        alpha: Int = 100,
        wireframe: Boolean = false,
        corner: Int = 100,
        shadow: Boolean = false,
        hint: Boolean = false,
        logo: WidgetBarLogo = WidgetBarLogo.GOOGLE,
        padH: Int = 0,
        padV: Int = 0,
        themedActions: Boolean = false,
    ) = WidgetBarStyle(
        fill, alpha, wireframe, corner, shadow, hint, logo, padH, padV,
        themedActions = themedActions,
    )

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // --- corner radius ---------------------------------------------------------------------------

    @Test
    fun `zero percent is square`() {
        assertEquals(0f, WidgetBarMetrics.cornerRadiusPx(0, 100f), 0.001f)
    }

    @Test
    fun `a hundred percent is a pill`() {
        assertEquals(50f, WidgetBarMetrics.cornerRadiusPx(100, 100f), 0.001f)
    }

    @Test
    fun `half way is a quarter of the height`() {
        assertEquals(25f, WidgetBarMetrics.cornerRadiusPx(50, 100f), 0.001f)
    }

    @Test
    fun `the radius never exceeds half the height`() {
        // A radius past half the height renders as a lens rather than a bar, and the slider is the
        // one control a user will happily drag to the end.
        for (percent in listOf(0, 50, 100, 500, -20)) {
            val radius = WidgetBarMetrics.cornerRadiusPx(percent, 40f)
            assertTrue("percent=$percent gave $radius", radius <= 20f)
            assertTrue("percent=$percent gave $radius", radius >= 0f)
        }
    }

    @Test
    fun `the radius scales with the bar, not with a stored dp`() {
        // This is why the setting is a percentage: the same pill must stay a pill when resized.
        assertEquals(28f, WidgetBarMetrics.cornerRadiusPx(100, 56f), 0.001f)
        assertEquals(50f, WidgetBarMetrics.cornerRadiusPx(100, 100f), 0.001f)
    }

    // --- alpha -----------------------------------------------------------------------------------

    @Test
    fun `full opacity is opaque`() {
        assertEquals(0xFF, WidgetBarMetrics.alphaOf(WidgetBarMetrics.applyAlpha(white, 100)))
    }

    @Test
    fun `zero opacity is fully transparent`() {
        assertEquals(0, WidgetBarMetrics.alphaOf(WidgetBarMetrics.applyAlpha(white, 0)))
    }

    @Test
    fun `alpha already in the colour is replaced, not multiplied`() {
        // Swatches arrive opaque and the slider owns transparency. Multiplying would make a colour
        // picked at 50% land at 25% the next time the slider was touched.
        val halfTransparentRed = 0x80FF0000.toInt()
        val result = WidgetBarMetrics.applyAlpha(halfTransparentRed, 100)
        assertEquals(0xFF, WidgetBarMetrics.alphaOf(result))
        assertEquals(0xFF, WidgetBarMetrics.redOf(result))
    }

    @Test
    fun `the colour channels survive an alpha change`() {
        val result = WidgetBarMetrics.applyAlpha(0xFF3366CC.toInt(), 40)
        assertEquals(0x33, WidgetBarMetrics.redOf(result))
        assertEquals(0x66, WidgetBarMetrics.greenOf(result))
        assertEquals(0xCC, WidgetBarMetrics.blueOf(result))
    }

    // --- fill and stroke -------------------------------------------------------------------------

    @Test
    fun `wireframe paints no fill`() {
        assertEquals(0, WidgetBarMetrics.alphaOf(WidgetBarMetrics.fillColor(white, style(wireframe = true))))
    }

    @Test
    fun `wireframe puts the chosen colour in the border`() {
        val stroke = WidgetBarMetrics.strokeColor(0xFF3366CC.toInt(), style(wireframe = true))
        assertEquals(0x33, WidgetBarMetrics.redOf(stroke))
        assertEquals(0xCC, WidgetBarMetrics.blueOf(stroke))
    }

    @Test
    fun `a filled bar draws no border at all`() {
        // The stroke is painted over the fill, so an edge in the fill colour is not invisible: the
        // two antialiased coverages composite along the whole rounded edge and every opacity below
        // 100% shows the seam as a darker rim.
        for (alpha in listOf(0, 25, 60, 100)) {
            val stroke = WidgetBarMetrics.strokeColor(0xFF3366CC.toInt(), style(alpha = alpha))
            assertEquals("alpha=$alpha", 0, WidgetBarMetrics.alphaOf(stroke))
        }
    }

    @Test
    fun `the border follows opacity in outline mode, where it is the whole bar`() {
        val outline = style(alpha = 40, wireframe = true)
        assertEquals(
            WidgetBarMetrics.alphaOf(WidgetBarMetrics.applyAlpha(0xFF3366CC.toInt(), 40)),
            WidgetBarMetrics.alphaOf(WidgetBarMetrics.strokeColor(0xFF3366CC.toInt(), outline)),
        )
    }

    // --- the room the shadow needs ---------------------------------------------------------------

    @Test
    fun `no shadow reserves nothing`() {
        val plain = style(shadow = false, padH = 0, padV = 0)
        assertEquals(0f, WidgetBarMetrics.insetHDp(plain), 0.001f)
        assertEquals(0f, WidgetBarMetrics.insetVDp(plain), 0.001f)
    }

    @Test
    fun `a shadow at a zero margin makes its own room`() {
        // Otherwise the blur runs past the bitmap and is cut off square by its edge, which reads as
        // a torn bar rather than as a shadow.
        val tight = style(shadow = true, padH = 0, padV = 0)
        assertEquals(WidgetBarMetrics.SHADOW_EXTENT_H_DP, WidgetBarMetrics.insetHDp(tight), 0.001f)
        assertEquals(WidgetBarMetrics.SHADOW_EXTENT_V_DP, WidgetBarMetrics.insetVDp(tight), 0.001f)
    }

    @Test
    fun `a margin wider than the shadow decides the size on its own`() {
        // The floor, not a sum: past the shadow's reach the toggle stops moving anything, so the
        // two controls are only coupled where they have to be.
        val roomy = style(shadow = true, padH = 20, padV = 12)
        assertEquals(20f, WidgetBarMetrics.insetHDp(roomy), 0.001f)
        assertEquals(12f, WidgetBarMetrics.insetVDp(roomy), 0.001f)
        assertEquals(
            WidgetBarMetrics.insetVDp(roomy),
            WidgetBarMetrics.insetVDp(style(shadow = false, padH = 20, padV = 12)),
            0.001f,
        )
    }

    @Test
    fun `the shadow always fits inside the room it asks for`() {
        // Three sigma of blur plus the offset, on the far side. The near side is smaller and gets
        // the same inset anyway, which is what keeps the bar centred in its cell.
        val reach = 3f * (WidgetBarMetrics.SHADOW_CSS_BLUR_DP / 2f)
        assertTrue(WidgetBarMetrics.SHADOW_EXTENT_H_DP >= reach + WidgetBarMetrics.SHADOW_DX_DP)
        assertTrue(WidgetBarMetrics.SHADOW_EXTENT_V_DP >= reach + WidgetBarMetrics.SHADOW_DY_DP)
    }

    @Test
    fun `the vertical margin cannot squeeze out the bar's own contents`() {
        // The slider runs further than a short cell can follow: at the 56dp minHeight, 24dp a side
        // would leave an 8dp bar behind a 22dp logo. The renderer asks for the margin through this,
        // which holds it to what leaves the tallest content room.
        val maxed = style(padV = WidgetBarMetrics.MAX_PADDING_V_DP)
        val shortCell = WidgetBarMetrics.effectiveInsetVDp(maxed, 56f)
        assertTrue(2 * shortCell + 22 <= 56f)
        // A tall cell is not held back at all.
        assertEquals(
            WidgetBarMetrics.MAX_PADDING_V_DP.toFloat(),
            WidgetBarMetrics.effectiveInsetVDp(maxed, 100f),
            0f,
        )
        assertTrue(WidgetBarMetrics.MAX_PADDING_V_DP >= WidgetBarMetrics.SHADOW_EXTENT_V_DP)
    }

    @Test
    fun `a fully transparent bar still has a shape to cast a shadow from`() {
        // At 0% opacity nothing is painted, and Skia derives a shadow from the alpha actually
        // drawn — so the shadow used to disappear with the fill. The renderer draws the shape
        // opaque and clips its own area away, which is only correct if the geometry survives;
        // guard the inputs that would make it degenerate.
        val invisible = style(alpha = 0, shadow = true, padV = 8)
        assertEquals(0, WidgetBarMetrics.alphaOf(WidgetBarMetrics.fillColor(white, invisible)))
        assertEquals(0, WidgetBarMetrics.alphaOf(WidgetBarMetrics.strokeColor(white, invisible)))
        assertTrue("shadow must not depend on anything being painted", invisible.shadow)
        assertTrue(WidgetBarMetrics.cornerRadiusPx(invisible.cornerPercent, 60f) > 0f)
    }

    @Test
    fun `padding defaults keep the bar full width`() {
        // The point of a zero side margin: the bar spans the cell rather than floating inside it.
        assertEquals(0, style().paddingHDp)
    }

    // --- content colour --------------------------------------------------------------------------

    @Test
    fun `a dark fill takes light content`() {
        assertTrue(WidgetBarMetrics.isLight(WidgetBarMetrics.contentColor(black, style())))
    }

    @Test
    fun `a light fill takes dark content`() {
        assertTrue(!WidgetBarMetrics.isLight(WidgetBarMetrics.contentColor(white, style())))
    }

    @Test
    fun `no fill colour can produce invisible content`() {
        // The whole reason content colour is derived rather than picked: white-on-white must be
        // unreachable, whatever the fill.
        for (colour in listOf(white, black, 0xFF3366CC.toInt(), 0xFFEEEEEE.toInt(), 0xFF111111.toInt())) {
            val content = WidgetBarMetrics.contentColor(colour, style())
            val gap = Math.abs(WidgetBarMetrics.luminance(content) - WidgetBarMetrics.luminance(colour))
            assertTrue("colour=$colour gap=$gap", gap > 0.3f)
        }
    }

    @Test
    fun `a see-through bar assumes an unknown wallpaper and goes light`() {
        assertTrue(WidgetBarMetrics.isLight(WidgetBarMetrics.contentColor(white, style(alpha = 20))))
        assertTrue(WidgetBarMetrics.isLight(WidgetBarMetrics.contentColor(white, style(wireframe = true))))
    }

    // --- icon tints ------------------------------------------------------------------------------

    private val accent = 0xFF336699.toInt()

    @Test
    fun `an action icon decides for itself unless themed`() {
        assertEquals(white, WidgetBarMetrics.actionTint(style(), true, white, accent))
        assertEquals(null, WidgetBarMetrics.actionTint(style(), false, white, accent))
    }

    @Test
    fun `themed actions take the accent, brand marks included`() {
        val themed = style(themedActions = true)
        assertEquals(accent, WidgetBarMetrics.actionTint(themed, false, white, accent))
        assertEquals(accent, WidgetBarMetrics.actionTint(themed, true, white, accent))
    }

    @Test
    fun `themed actions leave the logo alone`() {
        // The switch is for the actions only; the logo has its own option.
        assertEquals(null, WidgetBarMetrics.logoTint(WidgetBarLogo.GOOGLE, white, accent))
        assertEquals(accent, WidgetBarMetrics.logoTint(WidgetBarLogo.MAGNIFIER, white, accent))
    }

    @Test
    fun `the solid logo follows the accent`() {
        assertEquals(accent, WidgetBarMetrics.logoTint(WidgetBarLogo.GOOGLE_SOLID, white, accent))
    }

    // --- render size -----------------------------------------------------------------------------

    @Test
    fun `a zero size still yields a drawable bitmap`() {
        // Hosts report zero before the first layout pass, and Bitmap.createBitmap(0, ...) throws —
        // inside the widget update, which takes the host's update down rather than looking wrong.
        val (w, h) = WidgetBarMetrics.renderSizePx(0, 0, 3f)
        assertTrue(w >= 1)
        assertTrue(h >= 1)
    }

    @Test
    fun `a normal bar rasterises at its real pixel size`() {
        val (w, h) = WidgetBarMetrics.renderSizePx(250, 56, 3f)
        assertEquals(750, w)
        assertEquals(168, h)
    }

    @Test
    fun `an absurd size is capped`() {
        val (w, h) = WidgetBarMetrics.renderSizePx(99_999, 99_999, 4f)
        assertTrue("w=$w", w.toLong() * h.toLong() <= WidgetBarMetrics.MAX_AREA_PX)
    }

    @Test
    fun `the area cap scales both dimensions together`() {
        // Uniform scaling is what keeps fitXY a scale rather than a stretch — the corners go
        // elliptical the moment the aspect ratio shifts.
        val wideDp = 1000
        val tallDp = 200
        val (w, h) = WidgetBarMetrics.renderSizePx(wideDp, tallDp, 4f)
        val sourceRatio = wideDp.toFloat() / tallDp
        val renderedRatio = w.toFloat() / h
        assertEquals(sourceRatio, renderedRatio, 0.05f)
    }

    @Test
    fun `a preview that fits the budget is rasterised one to one`() {
        // The whole point: anything less than 1 is a resample, and the shadow's gradient is a few
        // pixels wide, so a resample facets it. A phone-width bar at 3x is nowhere near the budget.
        assertEquals(1f, WidgetBarMetrics.rasterScale(1179, 180), 0.0001f)
    }

    @Test
    fun `an oversized preview scales down to the budget`() {
        val scale = WidgetBarMetrics.rasterScale(2400, 420)
        assertTrue("scale=$scale", scale < 1f)
        val area = (2400 * scale).toLong() * (420 * scale).toLong()
        assertTrue("area=$area", area <= WidgetBarMetrics.MAX_AREA_PX)
    }

    @Test
    fun `a zero density does not collapse the bitmap`() {
        val (w, h) = WidgetBarMetrics.renderSizePx(250, 56, 0f)
        assertTrue(w >= 1)
        assertTrue(h >= 1)
    }
}
