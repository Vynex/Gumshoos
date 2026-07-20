package com.vynex.gumshoos.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.vynex.gumshoos.widget.WidgetBarMetrics

/**
 * Parsing and formatting for the hex field.
 *
 * Pure and separate from the composable so the accepted forms are pinned by tests rather than by
 * trying them by hand — a hex field that rejects something reasonable is a small, very annoying bug.
 */
object ColorHex {

    /** `#RRGGBB`, `RRGGBB`, `#AARRGGBB` or `AARRGGBB`, any case. Null when it isn't one of those. */
    fun parse(text: String): Int? {
        val cleaned = text.trim().removePrefix("#")
        if (cleaned.length != 6 && cleaned.length != 8) return null
        if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val value = cleaned.toLongOrNull(16) ?: return null
        // Six digits carry no alpha, so it is supplied here — a colour with alpha 0 would otherwise
        // be indistinguishable from black and silently invisible.
        return if (cleaned.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }

    /** Always six digits: alpha is the opacity slider's business, not the field's. */
    fun format(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}

/** Six reads as a palette rather than a list, and leaves swatches a comfortable size at 360dp. */
private const val GridColumns = 6
private val SwatchGap = 10.dp

/**
 * A collapsible colour picker.
 *
 * Collapsed by default, because a palette is tall and is consulted once then left alone — the
 * controls under it should not be pushed a screen away for the rest of the session. The header
 * carries the chosen colour while closed, so checking what is set costs no tap.
 */
@Composable
internal fun ColorPickerAccordion(
    selected: Int?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (Int) -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fullBleedRow()
                .clickable { onExpandedChange(!expanded) }
                .fillMaxWidth()
                .padding(horizontal = SettingsInset, vertical = 12.dp),
        ) {
            Text(
                "Colour",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation),
            )
            Spacer(Modifier.width(12.dp))
            SwatchCircle(
                color = Color(selected ?: 0xFF888888.toInt()),
                isSelected = false,
                modifier = Modifier.size(28.dp),
                onClick = { onExpandedChange(!expanded) },
            )
        }

        AnimatedVisibility(
            visible = expanded,
            // Height only. The default enter also scales in from a corner, which reads as the
            // palette flying in rather than the row opening.
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(Modifier.height(12.dp))

                PaletteLabel("System palette")
                SwatchGrid(systemPalette(), selected, onPick)

                Spacer(Modifier.height(16.dp))
                PaletteLabel("Fixed colours")
                SwatchGrid(FIXED, selected, onPick)

                Spacer(Modifier.height(16.dp))
                PaletteLabel("Custom")
                HexField(selected, onPick)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PaletteLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * Fixed columns rather than a scrolling strip.
 *
 * The horizontal rows this replaced kept most of the palette off-screen, which is a poor way to
 * present something whose entire purpose is comparison at a glance. Swatches take their width from
 * the grid, so the layout holds at any screen size.
 */
@Composable
private fun SwatchGrid(colors: List<Color>, selected: Int?, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SwatchGap)) {
        colors.chunked(GridColumns).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(SwatchGap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { colour -> Swatch(colour, selected, onPick, Modifier.weight(1f)) }
                // Keeps a short final row aligned with the ones above rather than stretching to fill.
                repeat(GridColumns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Int?, onPick: (Int) -> Unit, modifier: Modifier) {
    val argb = color.toArgb()
    // Compared on RGB only: the stored colour carries no alpha, and the opacity slider owns that.
    val isSelected = selected != null && (selected and 0xFFFFFF) == (argb and 0xFFFFFF)
    SwatchCircle(
        color = color,
        isSelected = isSelected,
        modifier = modifier.aspectRatio(1f),
        onClick = { onPick(argb) },
    )
}

@Composable
private fun SwatchCircle(
    color: Color,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        if (isSelected) {
            // Same luminance rule the bar uses for its own content, so the tick stays legible on a
            // swatch of any brightness rather than vanishing into the pale end of a ramp.
            val tick = if (WidgetBarMetrics.isLight(color.toArgb())) Color.Black else Color.White
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = tick,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun HexField(selected: Int?, onPick: (Int) -> Unit) {
    var text by remember(selected) { mutableStateOf(selected?.let { ColorHex.format(it) } ?: "") }
    val parsed = ColorHex.parse(text)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                // Applied as it becomes valid rather than on a confirm button, so the preview above
                // tracks typing the way every other control on this screen does.
                ColorHex.parse(it)?.let(onPick)
            },
            label = { Text("Hex") },
            placeholder = { Text("#1B6EF3") },
            isError = text.isNotBlank() && parsed == null,
            singleLine = true,
            modifier = Modifier.width(180.dp),
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(parsed ?: 0x00000000))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape)
        )
    }
}

/**
 * The wallpaper-derived ramps, in grid order.
 *
 * Six tones per ramp rather than the thirteen the system exposes: the full set is a wall of
 * near-identical pale steps, and six fills a row exactly. Accent and neutral ramps both appear
 * because a search bar is as often a surface as it is an accent.
 *
 * The separate "App theme" section this replaced is gone — those roles are derived from the same
 * wallpaper, so it offered the same colours under less obvious names.
 */
@Composable
private fun systemPalette(): List<Color> = listOf(
    android.R.color.system_accent1_100, android.R.color.system_accent1_200,
    android.R.color.system_accent1_400, android.R.color.system_accent1_600,
    android.R.color.system_accent1_800, android.R.color.system_accent1_900,

    android.R.color.system_accent2_100, android.R.color.system_accent2_200,
    android.R.color.system_accent2_400, android.R.color.system_accent2_600,
    android.R.color.system_accent2_800, android.R.color.system_accent2_900,

    android.R.color.system_accent3_100, android.R.color.system_accent3_200,
    android.R.color.system_accent3_400, android.R.color.system_accent3_600,
    android.R.color.system_accent3_800, android.R.color.system_accent3_900,

    android.R.color.system_neutral1_50, android.R.color.system_neutral1_100,
    android.R.color.system_neutral1_300, android.R.color.system_neutral1_600,
    android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,

    android.R.color.system_neutral2_50, android.R.color.system_neutral2_100,
    android.R.color.system_neutral2_300, android.R.color.system_neutral2_600,
    android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
).map { colorResource(it) }

/** Greys first, then saturated colours — the two things a wallpaper palette tends not to offer. */
private val FIXED = listOf(
    Color(0xFFFFFFFF), Color(0xFFE8E8E8), Color(0xFFCFCFCF),
    Color(0xFF9E9E9E), Color(0xFF6E6E6E), Color(0xFF3C3C3C),
    Color(0xFF1B1B1B), Color(0xFF000000), Color(0xFFD93025),
    Color(0xFFE8710A), Color(0xFFF9AB00), Color(0xFF1E8E3E),
    Color(0xFF12B5CB), Color(0xFF1A73E8), Color(0xFF3F51B5),
    Color(0xFF9334E6), Color(0xFFD01884), Color(0xFF5F6368),
)
