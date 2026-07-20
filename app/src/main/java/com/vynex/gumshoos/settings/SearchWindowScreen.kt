package com.vynex.gumshoos.settings

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.OverlayContent
import com.vynex.gumshoos.SEARCH_WINDOW_MAX_CORNER_DP
import com.vynex.gumshoos.SearchWindowStyle
import com.vynex.gumshoos.searchWindowCorner
import com.vynex.gumshoos.searchWindowSurface
import com.vynex.gumshoos.ui.theme.GumshoosTheme
import kotlin.math.roundToInt

/** Tall enough to show the card whole, without dominating the screen. */
private val PreviewHeight = 240.dp

/**
 * The size the overlay is laid out at before scaling.
 *
 * A typical phone width, and enough height for the settled card plus its top margin. The card's
 * geometry is fixed dp, so it has to be laid out at something realistic and then scaled.
 */
/** Notch size for the corner slider, in dp. */
private const val CORNER_STEP_DP = 2

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

        SettingsSection("Style")
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
            title = "Opaque",
            description = "Off lets a little of what's behind the window show through.",
            checked = style.alphaPercent >= GumshoosConfig.WINDOW_ALPHA_OPAQUE,
            onCheckedChange = { opaque ->
                GumshoosConfig.setSearchWindowAlpha(
                    context,
                    if (opaque) {
                        GumshoosConfig.WINDOW_ALPHA_OPAQUE
                    } else {
                        GumshoosConfig.WINDOW_ALPHA_TRANSPARENT
                    },
                )
            },
        )

        SettingsDivider()
        SettingsSection("Shape")
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

private fun cornerLabel(dp: Int): String = when (dp) {
    0 -> "Corners: square"
    else -> "Corners: ${dp}dp"
}

/**
 * The real search window, scaled to fit.
 *
 * Not a mock-up of it. Every setting on this screen — and every one that follows, icon size and
 * label style included — changes the overlay's own composable, so the only preview that can be
 * trusted is the composable itself. A second hand-built copy would agree today and drift on the
 * first change nobody thought to mirror.
 *
 * [OverlayContent] is given `interactive = false`, which suppresses the focus grab, the keyboard,
 * the back interception and dismissal — everything that reaches outside the composable and would be
 * nonsense inside a settings list. Nothing about how it *draws* is conditional on that flag.
 *
 * Laid out at a nominal phone width and scaled down, rather than letting it lay out in the strip:
 * the card's geometry is fixed dp measured against a real device, so squeezing it into 240dp would
 * render a layout that exists nowhere.
 */
@Composable
private fun SearchWindowPreview() {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(PreviewHeight)
            .clipToBounds(),
    ) {
        val scale = minOf(
            maxWidth / NominalWidth,
            PreviewHeight / NominalHeight,
        )
        WallpaperBackdrop(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .requiredSize(NominalWidth, NominalHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
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
}
