package com.vynex.gumshoos.widget

import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.overlayCellHeight
import com.vynex.gumshoos.overlaySettledHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search window's derived geometry.
 *
 * These were measured constants before the grid became configurable, so the derivation has one job
 * above all others: reproduce the measurements at the shipped defaults. It drifted once already —
 * a 1.17 line-height ratio put the cell at 84.08dp instead of 84 — which is invisible on screen and
 * exactly the sort of thing a test should hold rather than an eye.
 */
class OverlayGeometryTest {

    private val defaults = Triple(
        GumshoosConfig.DEFAULT_GRID_ROWS,
        GumshoosConfig.DEFAULT_ICON_SIZE_DP,
        GumshoosConfig.DEFAULT_LABEL_SIZE_SP,
    )

    @Test
    fun `the shipped defaults reproduce the measured cell`() {
        assertEquals(84f, overlayCellHeight(defaults.second, defaults.third).value, 0.01f)
    }

    @Test
    fun `the shipped defaults reproduce the measured card`() {
        // 12 top margin + 45 header + 48 tabs + 1 divider + 196 grid.
        val (rows, icon, label) = defaults
        assertEquals(302f, overlaySettledHeight(rows, icon, label).value, 0.01f)
    }

    @Test
    fun `a taller cell makes a taller card, once per row`() {
        val (_, icon, label) = defaults
        val one = overlaySettledHeight(1, icon, label).value
        val two = overlaySettledHeight(2, icon, label).value
        val three = overlaySettledHeight(3, icon, label).value
        // Each extra row costs a cell plus the spacing above it, and nothing else.
        val step = overlayCellHeight(icon, label).value + 8f
        assertEquals(step, two - one, 0.01f)
        assertEquals(step, three - two, 0.01f)
    }

    @Test
    fun `a single row carries no inter-row spacing`() {
        val (_, icon, label) = defaults
        // 12 + 45 + 48 + 1 + (20 + cell)
        val expected = 126f + overlayCellHeight(icon, label).value
        assertEquals(expected, overlaySettledHeight(1, icon, label).value, 0.01f)
    }

    @Test
    fun `every offered configuration grows monotonically`() {
        // The preview sizes its frame from the largest icon and label at a given row count, which
        // is only a safe upper bound if larger inputs never produce a shorter card.
        var previous = 0f
        for (icon in GumshoosConfig.MIN_ICON_SIZE_DP..GumshoosConfig.MAX_ICON_SIZE_DP) {
            val height = overlayCellHeight(icon, GumshoosConfig.DEFAULT_LABEL_SIZE_SP).value
            assertTrue("icon $icon shrank the cell", height > previous)
            previous = height
        }
        previous = 0f
        for (label in GumshoosConfig.MIN_LABEL_SIZE_SP..GumshoosConfig.MAX_LABEL_SIZE_SP) {
            val height = overlayCellHeight(GumshoosConfig.DEFAULT_ICON_SIZE_DP, label).value
            assertTrue("label $label shrank the cell", height > previous)
            previous = height
        }
    }

    @Test
    fun `the offered counts contain their own defaults`() {
        assertTrue(GumshoosConfig.DEFAULT_GRID_ROWS in
            GumshoosConfig.MIN_GRID_ROWS..GumshoosConfig.MAX_GRID_ROWS)
        assertTrue(GumshoosConfig.DEFAULT_GRID_COLUMNS in
            GumshoosConfig.MIN_GRID_COLUMNS..GumshoosConfig.MAX_GRID_COLUMNS)
    }

    @Test
    fun `the column count is not an input to the card's height`() {
        // Columns divide the cell's width and nothing else, which is why overlaySettledHeight does
        // not take them and the preview can size its frame from the row count alone. If a future
        // change makes the cell's height depend on the column count — shrinking icons to fit six,
        // say — this signature has to grow and the preview's reservation has to follow it.
        val (rows, icon, label) = defaults
        assertEquals(302f, overlaySettledHeight(rows, icon, label).value, 0.01f)
    }

    @Test
    fun `the largest configuration is what the preview reserves`() {
        val max = overlaySettledHeight(
            GumshoosConfig.MAX_GRID_ROWS,
            GumshoosConfig.MAX_ICON_SIZE_DP,
            GumshoosConfig.MAX_LABEL_SIZE_SP,
        ).value
        val shipped = overlaySettledHeight(defaults.first, defaults.second, defaults.third).value
        assertTrue("the biggest card must exceed the shipped one", max > shipped)
    }
}
