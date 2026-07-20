package com.vynex.gumshoos

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the search window itself is drawn.
 *
 * Deliberately not [com.vynex.gumshoos.widget.WidgetBarStyle], despite both offering a colour, an
 * opacity and a corner. The two resolve "adaptive" against different things — the widget sits on
 * the wallpaper and reads the system palette directly, while this is a Compose surface inside a
 * themed window — and the corner is a dp here rather than a fraction of height, because the card is
 * a card rather than a bar whose height the launcher decides.
 */
data class SearchWindowStyle(
    val adaptive: Boolean,
    val color: Int,
    /** 0..100. */
    val alphaPercent: Int,
    val cornerRadiusDp: Int,
)

/** Bounds for the corner slider. 0 is a hard rectangle; the upper end is well past the default. */
const val SEARCH_WINDOW_MAX_CORNER_DP = 40

/**
 * The card's colour, adaptive resolved and opacity applied.
 *
 * Composable so the overlay and its preview cannot disagree: the same call decides both, the way
 * the widget's preview shares the widget's renderer. Adaptive is the theme's own surface, which is
 * wallpaper-derived — so leaving it alone still tracks the system rather than pinning a colour.
 */
@Composable
fun searchWindowSurface(style: SearchWindowStyle): Color {
    val base = if (style.adaptive) MaterialTheme.colorScheme.surface else Color(style.color)
    return base.copy(alpha = style.alphaPercent.coerceIn(0, 100) / 100f)
}

/** The settled corner radius. The entrance animates up to this from a pill; see OverlayContent. */
fun searchWindowCorner(style: SearchWindowStyle): Dp =
    style.cornerRadiusDp.coerceIn(0, SEARCH_WINDOW_MAX_CORNER_DP).dp
