package com.vynex.gumshoos.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.SEARCH_WINDOW_MAX_CORNER_DP
import com.vynex.gumshoos.SearchWindowStyle
import com.vynex.gumshoos.searchWindowCorner
import com.vynex.gumshoos.searchWindowSurface
import com.vynex.gumshoos.ui.theme.GumshoosTheme
import kotlin.math.roundToInt

/** Tall enough to show the card's corners and a row of content, without dominating the screen. */
private val PreviewHeight = 170.dp

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

        Spacer(Modifier.height(16.dp))
        var alpha by remember(style.alphaPercent) { mutableStateOf(style.alphaPercent.toFloat()) }
        SettingsNote("Opacity: ${alpha.roundToInt()}%")
        Slider(
            value = alpha,
            onValueChange = {
                alpha = it
                GumshoosConfig.setSearchWindowAlpha(context, it.roundToInt())
            },
            valueRange = 0f..100f,
            steps = 19,
            modifier = Modifier.fillMaxWidth(),
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
            steps = SEARCH_WINDOW_MAX_CORNER_DP - 1,
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
 * The window's card, over the wallpaper.
 *
 * Wrapped in [GumshoosTheme] with dark forced on, exactly as the overlay is — the card takes its
 * adaptive colour from the theme, so previewing it under the settings theme would show a colour the
 * real window never wears.
 *
 * Only the header row is drawn. Reproducing the grid would mean maintaining a second copy of the
 * overlay's layout for no gain: what these settings change is the card, and the header is enough to
 * judge the corner and how the contents read against the fill.
 */
@Composable
private fun SearchWindowPreview() {
    WallpaperBackdrop(
        Modifier
            .fillMaxWidth()
            .height(PreviewHeight),
    ) {
        GumshoosTheme(darkTheme = true, dynamicColor = true) {
            val style = GumshoosConfig.searchWindowStyle
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = PreviewPadding, vertical = PreviewPadding)
                    .fillMaxWidth()
                    .height(PreviewHeight - PreviewPadding * 2)
                    .clip(RoundedCornerShape(searchWindowCorner(style)))
                    .background(searchWindowSurface(style)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(45.dp)
                        .padding(horizontal = 14.dp),
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Search",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                    )
                }
                // Two faint bars standing in for the app grid, so the fill can be judged against
                // content rather than against an empty panel.
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, bottom = 16.dp),
                ) {
                    repeat(2) {
                        Box(
                            Modifier
                                .padding(vertical = 5.dp)
                                .height(10.dp)
                                .width(if (it == 0) 150.dp else 96.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                                ),
                        )
                    }
                }
            }
        }
    }
}
