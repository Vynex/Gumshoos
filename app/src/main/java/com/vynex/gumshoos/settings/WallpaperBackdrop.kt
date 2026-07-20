package com.vynex.gumshoos.settings

import android.app.WallpaperManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Something wallpaper-derived to preview the search bar against.
 *
 * A widget preview on a flat panel says very little: opacity, outline mode and the margins are all
 * about how the bar sits on whatever is behind it, and none of them read against a plain rectangle.
 *
 * **This is the wallpaper's colours, not the wallpaper.** Reading the actual bitmap is not open to
 * this app at all — `WallpaperManager.peekDrawable` and `getDrawable` require either
 * `MANAGE_EXTERNAL_STORAGE`, which is All-files access and wildly out of proportion to a preview
 * backdrop, or `READ_WALLPAPER_INTERNAL`, which is signature-level. Lint catches the attempt; a
 * runtime `SecurityException` would otherwise have been swallowed by a fallback and never noticed.
 *
 * `getWallpaperColors` needs no permission and returns the tones the system itself derives its
 * theme from, so the backdrop is genuinely taken from the wallpaper even though the image is off
 * limits — and it keeps working for a live wallpaper, which has no bitmap to read in the first
 * place.
 */
@Composable
internal fun WallpaperBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val colours = remember { wallpaperColours(context) }

    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    // Diagonal, so a two-tone wallpaper reads as depth behind the bar rather than
                    // as a flat band the bar happens to sit on.
                    Brush.linearGradient(
                        colors = colours,
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    )
                )
        )
        content()
    }
}

/**
 * The wallpaper's palette, darkest usable pair first.
 *
 * Falls back to a neutral pair rather than to nothing: an unset or unreadable wallpaper should
 * still give the preview something to sit on.
 */
private fun wallpaperColours(context: Context): List<Color> {
    val colours = runCatching {
        WallpaperManager.getInstance(context)?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
    }.getOrNull() ?: return NEUTRAL

    val stops = listOfNotNull(
        Color(colours.primaryColor.toArgb()),
        colours.secondaryColor?.let { Color(it.toArgb()) },
        colours.tertiaryColor?.let { Color(it.toArgb()) },
    )
    // One stop is not a gradient. Pair it with a darkened copy so there is still some fall-off.
    return when {
        stops.isEmpty() -> NEUTRAL
        stops.size == 1 -> listOf(stops.first(), stops.first().darken())
        else -> stops
    }
}

private fun Color.darken(): Color = Color(red * 0.6f, green * 0.6f, blue * 0.6f, alpha)

/** Used when no wallpaper colours are available; mid-grey reads under both light and dark bars. */
private val NEUTRAL = listOf(Color(0xFF3A3A3A), Color(0xFF232323))
