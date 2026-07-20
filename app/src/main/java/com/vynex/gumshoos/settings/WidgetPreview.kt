package com.vynex.gumshoos.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Wallpaper visible around a previewed widget.
 *
 * Some is the point: a widget drawn edge to edge against the backdrop shows nothing about how it
 * sits on a home screen, which is what the margin and size settings are for.
 */
internal val PreviewPadding = 16.dp

/**
 * The height of a placed widget of [type], if there is one.
 *
 * Previews are drawn at the size of a widget you have actually placed rather than at a guess.
 * Everything these screens adjust is in dp, so the effect depends entirely on how large the cell
 * is — a fixed-height preview makes an 8dp margin look generous here and thin on a tall launcher
 * row, and an icon size look right at one cell size and wrong at another.
 *
 * Null when none is placed, or when the host hasn't reported a size yet — the same pre-layout zero
 * the renderer guards against.
 */
internal fun placedWidgetHeight(
    context: Context,
    type: Class<*>,
    min: Dp,
    max: Dp,
): Dp? {
    val manager = AppWidgetManager.getInstance(context) ?: return null
    val ids = runCatching {
        manager.getAppWidgetIds(ComponentName(context, type))
    }.getOrNull()?.toList().orEmpty()

    val heightDp = ids.firstNotNullOfOrNull { id ->
        runCatching {
            manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        }.getOrNull()?.takeIf { it > 0 }
    } ?: return null

    return heightDp.toFloat().dp.coerceIn(min, max)
}
