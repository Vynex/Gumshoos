package com.vynex.gumshoos.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/** Fallback cell size, used when none is placed to measure. Close to a typical launcher cell. */
private val DefaultCellSize = 88.dp
private val MinCellSize = 64.dp
private val MaxCellSize = 140.dp

/** The icon's own range, in dp. Below 32 it is lost in the cell; above 64 it fills it. */
private const val MIN_ICON_DP = 32
private const val MAX_ICON_DP = 64

/** How far the icon may be shifted from centre, either way. */
private const val MAX_OFFSET_DP = 16

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

    fun push() = SearchWidgetSmallProvider.refreshAll(context)

    SettingsScaffoldWithHeader(
        title = "App icon widget",
        onBack = onBack,
        // Read straight from config rather than from screen-local state. Config is observable and
        // the sliders write to it as they move, so the preview tracks a drag without a second copy
        // of the value to keep in step — which is what the reset button used to have to remember.
        header = {
            WidgetIconPreview(
                GumshoosConfig.widgetIconSizeDp,
                GumshoosConfig.widgetIconOffsetDp,
            )
        },
    ) {
        SettingsNote(
            "How the icon sits in its launcher cell. Cells are much larger than an app icon, so " +
                "filling one is what makes a widget read as oversized next to real icons.",
        )

        SettingsGroup {
            SettingsSliderRow(
                label = "Icon size",
                value = GumshoosConfig.widgetIconSizeDp,
                min = MIN_ICON_DP,
                max = MAX_ICON_DP,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setWidgetIconSizeDp(context, it) },
                // Written as it moves so the preview tracks the drag; the widget itself is only
                // re-pushed on release, since that crosses to the launcher.
                onValueFinished = { push() },
            )
            SettingsSliderRow(
                label = "Vertical offset",
                value = GumshoosConfig.widgetIconOffsetDp,
                min = -MAX_OFFSET_DP,
                max = MAX_OFFSET_DP,
                valueText = { if (it == 0) "Centred" else "${it}dp" },
                onValue = { GumshoosConfig.setWidgetIconOffsetDp(context, it) },
                onValueFinished = { push() },
            )
        }

        SettingsGroup {
            SettingsActionRow(
                title = "Reset to defaults",
                summary = "Back to ${GumshoosConfig.DEFAULT_WIDGET_ICON_SIZE_DP}dp and centred.",
                imageVector = Icons.Filled.Refresh,
                onClick = {
                    GumshoosConfig.setWidgetIconSizeDp(
                        context, GumshoosConfig.DEFAULT_WIDGET_ICON_SIZE_DP,
                    )
                    GumshoosConfig.setWidgetIconOffsetDp(
                        context, GumshoosConfig.DEFAULT_WIDGET_ICON_OFFSET_DP,
                    )
                    push()
                },
            )
        }
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
