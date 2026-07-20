package com.vynex.gumshoos.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vynex.gumshoos.AppRepository
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.OverlayContent
import com.vynex.gumshoos.overlaySettledHeight
import com.vynex.gumshoos.SEARCH_WINDOW_MAX_CORNER_DP
import com.vynex.gumshoos.SearchWindowStyle
import com.vynex.gumshoos.searchWindowCorner
import com.vynex.gumshoos.searchWindowSurface
import com.vynex.gumshoos.ui.theme.GumshoosTheme
import kotlin.math.roundToInt

/**
 * The size the overlay is laid out at before scaling.
 *
 * A typical phone width, and enough height for the settled card plus its top margin. The card's
 * geometry is fixed dp, so it has to be laid out at something realistic and then scaled.
 */
/** Notch size for the corner slider, in dp. */
private const val CORNER_STEP_DP = 2

/** Icons move in twos: single dp is finer than the difference is visible at this size. */
private const val ICON_STEP_DP = 2

/**
 * Wallpaper left visible above and below the window.
 *
 * Uneven on purpose: the window sits near the top of a real screen, so a little room above it and
 * less below reads the way it actually appears rather than as a card floating in a box.
 */
private val PreviewTopSlack = 10.dp
private val PreviewBottomSlack = 6.dp

private val NominalWidth = 380.dp
private val NominalHeight = 330.dp

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

        SettingsSection("Window")
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
        )

        Spacer(Modifier.height(8.dp))
        SettingsSwitchRow(
            title = "Transparent",
            description = "Let a little of what's behind the window show through",
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


        Spacer(Modifier.height(8.dp))
        var corner by remember(style.cornerRadiusDp) {
            mutableStateOf(style.cornerRadiusDp.toFloat())
        }
        SettingsNote(cornerLabel(corner.roundToInt()))
        Slider(
            value = corner,
            onValueChange = {
                corner = it
                GumshoosConfig.setSearchWindowCorner(context, it.roundToInt())
            },
            valueRange = 0f..SEARCH_WINDOW_MAX_CORNER_DP.toFloat(),
            // 2dp notches. Single-dp steps gave forty positions to distinguish between, and a
            // corner radius is not a measurement anyone reads off a slider — it is a look.
            steps = SEARCH_WINDOW_MAX_CORNER_DP / CORNER_STEP_DP - 1,
            modifier = Modifier.fillMaxWidth(),
        )

        SettingsDivider()
        SettingsSection("Apps")
        SettingsNote(
            "The grid of results. Five to a row, so a larger icon leaves less room for its label.",
        )

        Spacer(Modifier.height(4.dp))
        Text("Rows", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            (GumshoosConfig.MIN_GRID_ROWS..GumshoosConfig.MAX_GRID_ROWS).forEach { rows ->
                val chosen = GumshoosConfig.overlayGridRows == rows
                OutlinedButton(
                    onClick = { GumshoosConfig.setOverlayGridRows(context, rows) },
                    colors = if (chosen) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("$rows") }
            }
        }

        Spacer(Modifier.height(8.dp))
        StepSlider(
            label = "Icon size",
            value = GumshoosConfig.overlayIconSize,
            min = GumshoosConfig.MIN_ICON_SIZE_DP,
            max = GumshoosConfig.MAX_ICON_SIZE_DP,
            step = ICON_STEP_DP,
            unit = "dp",
            onValue = { GumshoosConfig.setOverlayIconSize(context, it) },
        )

        Spacer(Modifier.height(8.dp))
        StepSlider(
            label = "Label size",
            value = GumshoosConfig.overlayLabelSize,
            min = GumshoosConfig.MIN_LABEL_SIZE_SP,
            max = GumshoosConfig.MAX_LABEL_SIZE_SP,
            step = 1,
            unit = "sp",
            onValue = { GumshoosConfig.setOverlayLabelSize(context, it) },
        )

        SettingsDivider()
        OutlinedButton(
            onClick = { GumshoosConfig.resetSearchWindow(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reset to defaults") }
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
 * A slider over whole units, reporting its value in the label.
 *
 * Local state so the label tracks the drag; config is written on every change because the preview
 * above is the point of the screen, and it is reading config rather than the slider.
 */
@Composable
private fun StepSlider(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    unit: String,
    onValue: (Int) -> Unit,
) {
    var current by remember(value) { mutableStateOf(value.toFloat()) }
    SettingsNote("$label: ${current.roundToInt()}$unit")
    Slider(
        value = current,
        onValueChange = {
            current = it
            onValue(it.roundToInt())
        },
        valueRange = min.toFloat()..max.toFloat(),
        steps = ((max - min) / step) - 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun cornerLabel(dp: Int): String = when (dp) {
    0 -> "Corners: square"
    else -> "Corners: ${dp}dp"
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

    val stripHeight = overlaySettledHeight(
        rows = GumshoosConfig.overlayGridRows,
        iconDp = GumshoosConfig.MAX_ICON_SIZE_DP,
        labelSp = GumshoosConfig.MAX_LABEL_SIZE_SP,
    ) + PreviewTopSlack + PreviewBottomSlack

    WallpaperBackdrop(
        Modifier
            .fillMaxWidth()
            .height(stripHeight)
            .clipToBounds(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = PreviewTopSlack)
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
