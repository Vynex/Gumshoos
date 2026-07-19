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
    ) = WidgetBarStyle(fill, alpha, wireframe, corner, shadow, hint, logo, padH, padV)

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
    fun `a filled bar borders itself in its own colour`() {
        // Not a fixed white hairline: that showed as a pale rim against any fill that wasn't
        // near-white, and read as a rendering fault rather than a border.
        val fill = 0xFF3366CC.toInt()
        assertEquals(
            WidgetBarMetrics.fillColor(fill, style()),
            WidgetBarMetrics.strokeColor(fill, style()),
        )
    }

    @Test
    fun `the border follows opacity as the fill does`() {
        val stroke = WidgetBarMetrics.strokeColor(0xFF3366CC.toInt(), style(alpha = 40))
        assertEquals(WidgetBarMetrics.alphaOf(WidgetBarMetrics.applyAlpha(0xFF3366CC.toInt(), 40)),
            WidgetBarMetrics.alphaOf(stroke))
    }

    // --- padding is independent of the shadow ----------------------------------------------------

    @Test
    fun `the shadow does not change the bar geometry`() {
        // These were coupled once: the shadow reserved room by insetting the bar, so turning it on
        // silently made the bar shorter and neither could be set without disturbing the other.
        val withShadow = style(shadow = true, padV = 8)
        val without = style(shadow = false, padV = 8)
        assertEquals(withShadow.paddingVDp, without.paddingVDp)
        assertEquals(
            WidgetBarMetrics.cornerRadiusPx(100, 100f - 2 * withShadow.paddingVDp),
            WidgetBarMetrics.cornerRadiusPx(100, 100f - 2 * without.paddingVDp),
            0.001f,
        )
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
    fun `a zero density does not collapse the bitmap`() {
        val (w, h) = WidgetBarMetrics.renderSizePx(250, 56, 0f)
        assertTrue(w >= 1)
        assertTrue(h >= 1)
    }
}
