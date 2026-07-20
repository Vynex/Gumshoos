package com.vynex.gumshoos.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.R
import com.vynex.gumshoos.SearchWidgetSmallProvider
import kotlin.math.roundToInt

/** Fallback cell size, used when no 1x1 is placed to measure. Close to a typical launcher cell. */
private val DefaultCellSize = 88.dp
private val MinCellSize = 64.dp
private val MaxCellSize = 140.dp

/**
 * Size and position of the single-cell widget.
 *
 * Its own screen, with the same shape as the search bar's: a preview pinned above a scrolling list,
 * because both settings here are about how the icon sits in its cell and neither can be judged
 * without seeing it.
 */
@Composable
internal fun WidgetIconScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(GumshoosConfig.widgetIconSizeDp.toFloat()) }
    var offset by remember { mutableStateOf(GumshoosConfig.widgetIconOffsetDp.toFloat()) }

    fun push() = SearchWidgetSmallProvider.refreshAll(context)

    SettingsScaffoldWithHeader(
        title = "1x1 widget",
        onBack = onBack,
        header = { WidgetIconPreview(size.roundToInt(), offset.roundToInt()) },
    ) {
        SettingsNote(
            "How the icon sits in its launcher cell. Cells are much larger than an app icon, so " +
                "filling one is what makes a widget read as oversized next to real icons.",
        )

        SettingsSection("Size")
        SettingsNote("Icon size: ${size.roundToInt()}dp")
        Slider(
            value = size,
            onValueChange = {
                size = it
                // Written as it moves so the preview tracks the drag; the widget itself is only
                // re-pushed on release, since that crosses to the launcher.
                GumshoosConfig.setWidgetIconSizeDp(context, it.roundToInt())
            },
            onValueChangeFinished = { push() },
            valueRange = 32f..64f,
            steps = 31,
            modifier = Modifier.fillMaxWidth(),
        )

        SettingsSection("Position")
        SettingsNote(
            "Vertical offset: ${offset.roundToInt()}dp — 0 centres the icon, positive moves it down.",
        )
        Slider(
            value = offset,
            onValueChange = {
                offset = it
                GumshoosConfig.setWidgetIconOffsetDp(context, it.roundToInt())
            },
            onValueChangeFinished = { push() },
            valueRange = -16f..16f,
            steps = 31,
            modifier = Modifier.fillMaxWidth(),
        )

        SettingsDivider()
        OutlinedButton(
            onClick = {
                size = GumshoosConfig.DEFAULT_WIDGET_ICON_SIZE_DP.toFloat()
                offset = GumshoosConfig.DEFAULT_WIDGET_ICON_OFFSET_DP.toFloat()
                GumshoosConfig.setWidgetIconSizeDp(context, size.roundToInt())
                GumshoosConfig.setWidgetIconOffsetDp(context, offset.roundToInt())
                push()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reset to defaults") }
    }
}

/**
 * The icon in a cell, over the wallpaper.
 *
 * The cell outline is not drawn. What matters is the icon against the wallpaper at the size it will
 * actually appear, and a visible box would invite tuning the icon to the box rather than to the app
 * icons it sits beside.
 *
 * The offset is a plain shift here, where the widget applies it as a doubled margin pair — a
 * FrameLayout centres a child *within* its margins, so a top margin of 2N moves it down by N. The
 * two land in the same place; only the mechanism differs, because RemoteViews has no offset.
 */
@Composable
private fun WidgetIconPreview(iconSizeDp: Int, offsetDp: Int) {
    val context = LocalContext.current
    val cell = remember {
        placedWidgetHeight(
            context, SearchWidgetSmallProvider::class.java, MinCellSize, MaxCellSize,
        ) ?: DefaultCellSize
    }

    WallpaperBackdrop(
        Modifier
            .fillMaxWidth()
            .height(cell + PreviewPadding * 2),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .padding(vertical = PreviewPadding)
                .size(cell),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_widget_search_app),
                contentDescription = null,
                modifier = Modifier
                    .size(iconSizeDp.dp)
                    .offset(y = offsetDp.dp),
            )
        }
    }
}
