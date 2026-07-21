package com.vynex.gumshoos.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vynex.gumshoos.AppRepository
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.OverlayCardTopMargin
import com.vynex.gumshoos.OverlayContent
import com.vynex.gumshoos.overlaySettledHeight
import com.vynex.gumshoos.SEARCH_WINDOW_MAX_CORNER_DP
import com.vynex.gumshoos.SearchWindowStyle

/** Notch size for the corner slider, in dp. */
private const val CORNER_STEP_DP = 2

/** Icons move in twos: single dp is finer than the difference is visible at this size. */
private const val ICON_STEP_DP = 2

/**
 * Wallpaper left visible below the window, matching what it leaves above itself.
 *
 * Nothing is added at the top: the card already carries its own margin there, and that margin is
 * real — the window genuinely sits that far down a screen. Taking the figure from the card rather
 * than restating it keeps the two sides equal if it ever changes.
 */
private val PreviewBottomSlack: Dp
    @Composable get() = OverlayCardTopMargin

/**
 * How the search window itself looks.
 *
 * Same shape as the widget screens — preview pinned above, controls scrolling below — because the
 * question is the same one: these settings are only judgeable against the thing they change.
 */
@Composable
internal fun SearchWindowScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val style = GumshoosConfig.searchWindowStyle
    var paletteOpen by remember { mutableStateOf(false) }

    SettingsScaffoldWithHeader(
        title = "Search window",
        onBack = onBack,
        header = { SearchWindowPreview() },
    ) {
        SettingsNote(
            "The card the search window is drawn on. It appears over whatever is on screen, so " +
                "opacity decides how much of that shows through.",
        )

        ColorPickerAccordion(
            // The overlay is hard-coded to the dark theme, so adaptive has to be resolved under it
            // rather than under the settings theme — otherwise the swatch shown here is a colour
            // the window never uses.
            currentColor = windowSwatch(style),
            selected = if (style.adaptive) null else style.color,
            adaptive = style.adaptive,
            onAdaptiveChange = { GumshoosConfig.setSearchWindowAdaptive(context, it) },
            expanded = paletteOpen,
            onExpandedChange = { paletteOpen = it },
            onPick = { GumshoosConfig.setSearchWindowColor(context, it) },
            // It draws its own card, so it takes the margin a SettingsGroup would have given it
            // rather than being wrapped in one and ending up a card inside a card.
            modifier = Modifier.padding(vertical = 8.dp),
        )

        SettingsGroup(title = "Window") {
            SettingsSwitchRow(
                title = "Transparent",
                description = "Lets a little of what's behind the window show through.",
                checked = style.alphaPercent < GumshoosConfig.WINDOW_ALPHA_OPAQUE,
                onCheckedChange = { transparent ->
                    GumshoosConfig.setSearchWindowAlpha(
                        context,
                        if (transparent) {
                            GumshoosConfig.WINDOW_ALPHA_TRANSPARENT
                        } else {
                            GumshoosConfig.WINDOW_ALPHA_OPAQUE
                        },
                    )
                },
            )
            SettingsSliderRow(
                label = "Corners",
                value = style.cornerRadiusDp,
                min = 0,
                max = SEARCH_WINDOW_MAX_CORNER_DP,
                // 2dp notches. Single-dp steps gave forty positions to distinguish between, and a
                // corner radius is not a measurement anyone reads off a slider — it is a look.
                step = CORNER_STEP_DP,
                valueText = { if (it == 0) "Square" else "${it}dp" },
                onValue = { GumshoosConfig.setSearchWindowCorner(context, it) },
            )
        }

        SettingsGroup(
            title = "Apps",
            note = "More columns means a narrower cell, so a large icon and six columns leave " +
                "little room for the label.",
        ) {
            SettingsChoiceRow(
                label = "Rows",
                values = (GumshoosConfig.MIN_GRID_ROWS..GumshoosConfig.MAX_GRID_ROWS).toList(),
                selected = GumshoosConfig.overlayGridRows,
                onSelect = { GumshoosConfig.setOverlayGridRows(context, it) },
            )
            SettingsChoiceRow(
                label = "Columns",
                values = (GumshoosConfig.MIN_GRID_COLUMNS..GumshoosConfig.MAX_GRID_COLUMNS)
                    .toList(),
                selected = GumshoosConfig.overlayGridColumns,
                onSelect = { GumshoosConfig.setOverlayGridColumns(context, it) },
            )
            SettingsSliderRow(
                label = "Icon size",
                value = GumshoosConfig.overlayIconSize,
                min = GumshoosConfig.MIN_ICON_SIZE_DP,
                max = GumshoosConfig.MAX_ICON_SIZE_DP,
                step = ICON_STEP_DP,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setOverlayIconSize(context, it) },
            )
            SettingsSliderRow(
                label = "Label size",
                value = GumshoosConfig.overlayLabelSize,
                min = GumshoosConfig.MIN_LABEL_SIZE_SP,
                max = GumshoosConfig.MAX_LABEL_SIZE_SP,
                valueText = { "${it}sp" },
                onValue = { GumshoosConfig.setOverlayLabelSize(context, it) },
            )
        }

        SettingsGroup {
            SettingsActionRow(
                title = "Reset to defaults",
                summary = "Colour, shape and grid go back to how they started.",
                imageVector = Icons.Filled.Refresh,
                onClick = { GumshoosConfig.resetSearchWindow(context) },
            )
        }
    }
}

/**
 * The colour the header swatch should show.
 *
 * The overlay runs under the dark scheme unconditionally, so an adaptive window's colour has to be
 * read from `dynamicDarkColorScheme` rather than from the ambient settings theme — otherwise the
 * swatch advertises a colour the window never wears.
 */
@Composable
private fun windowSwatch(style: SearchWindowStyle): Int {
    val context = LocalContext.current
    return if (style.adaptive) {
        dynamicDarkColorScheme(context).surface.toArgb()
    } else {
        style.color
    }
}

/**
 * The real search window, at real size.
 *
 * Not a mock-up and not scaled. Every setting on this screen changes the overlay's own composable,
 * so the only preview that can be trusted is the composable itself — and it is laid out at the
 * width it will really occupy, so what is on screen here is what appears on the home screen.
 *
 * [OverlayContent] is given `interactive = false`, which suppresses the focus grab, the keyboard,
 * the back interception, dismissal, and all animation. Nothing about how it *draws* depends on that
 * flag; a preview that animates its own entrance every time a slider moves reads as a fault.
 *
 * The strip is sized for the tallest card the current row count allows, not for the current one, so
 * dragging an icon or label slider resizes the card inside a frame that holds still rather than
 * shoving the controls below it up and down.
 */
@Composable
private fun SearchWindowPreview() {
    val context = LocalContext.current

    // Without an index the grid says "Loading apps…", which previews nothing. Settings runs in the
    // same process as the service, so this is the very same index the window will use — real apps
    // rather than stand-ins, and it costs a settings screen a few hundred milliseconds off the main
    // thread when the process happens to be cold.
    LaunchedEffect(Unit) {
        if (!AppRepository.loaded) runCatching { AppRepository.load(context) }
    }

    // Sized to the card as configured, not to the largest it could be. Reserving for the maximum
    // held the frame still while a slider was dragged, at the cost of dead wallpaper below the
    // window at every setting short of the maximum — 44dp of it at the defaults. The frame now
    // tracks the card, which does mean the controls below shift while an icon or label slider is
    // dragged; that reads as the window resizing, which is what is actually happening.
    val stripHeight = overlaySettledHeight(
        rows = GumshoosConfig.overlayGridRows,
        iconDp = GumshoosConfig.overlayIconSize,
        labelSp = GumshoosConfig.overlayLabelSize,
    ) + PreviewBottomSlack

    WallpaperBackdrop(
        Modifier
            .fillMaxWidth()
            .height(stripHeight)
            .clipToBounds(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // Swallows touches before the window sees them. `interactive = false` already stops
                // the overlay reaching outside itself, but its own contents are still live — a tap
                // would launch an app and a swipe would change tab, from a settings screen.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes
                                .forEach { it.consume() }
                        }
                    }
                },
        ) {
            OverlayContent(
                visible = true,
                resetToken = 0,
                onDismiss = {},
                interactive = false,
            )
        }
    }
}
