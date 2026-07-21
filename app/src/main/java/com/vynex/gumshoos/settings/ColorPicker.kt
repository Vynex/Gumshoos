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
import androidx.compose.material3.Switch
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
    /** What the bar is actually filled with right now, adaptive or not — shown in the header. */
    currentColor: Int,
    /** Null while adaptive, so no swatch claims to be the chosen one. */
    selected: Int?,
    adaptive: Boolean,
    onAdaptiveChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (Int) -> Unit,
    /**
     * Carries the vertical margin a [SettingsGroup] would have supplied. This draws its own card, so
     * wrapping it in a group would put a card inside a card; it sits *as* a group instead.
     */
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    // Raised onto its own surface so the palette reads as one object rather than as loose rows in
    // the list — the same container a SettingsGroup would have given it, drawn here because the
    // header row has to sit inside the card to be the thing that opens it.
    Column(
        modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(settingsCardColour()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
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
                color = Color(currentColor),
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
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                // Inside the card rather than above it: it decides where the colour comes from, so
                // it belongs with the colours. No description — the switch and the swatches beside
                // it say what it does more directly than a sentence would.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        "Adaptive",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = adaptive, onCheckedChange = onAdaptiveChange)
                }

                Spacer(Modifier.height(4.dp))
                PaletteLabel("System palette")
                SwatchGrid(systemPalette(), selected, onPick)

                Spacer(Modifier.height(16.dp))
                PaletteLabel("Custom")
                HexField(selected, onPick)
                // Matches the inset the card holds its content at on every other side. At 8dp the
                // field sat closer to the card's bottom edge than to anything beside it.
                Spacer(Modifier.height(SettingsInset))
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

/**
 * The hex input and the colour it currently describes.
 *
 * No floating label. The field had one reading "Hex" directly under a heading reading "Custom",
 * above a placeholder reading "#1B6EF3" — three labels for one input, and the floating one is what
 * pushed the text off centre and made a field of M3's minimum height look taller than it is. 56dp
 * *is* the spec minimum (`TextFieldDefaults.MinHeight`), so the height is not the thing to fight;
 * the label was.
 *
 * The field takes the remaining width so the swatch lands on the right edge of the card, in line
 * with the last column of the palette above it. At a fixed width it stopped short of the edge and
 * the swatch floated in the middle of nothing.
 */
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
            placeholder = { Text("#1B6EF3") },
            isError = text.isNotBlank() && parsed == null,
            singleLine = true,
            shape = SettingsCardShape,
            modifier = Modifier.weight(1f),
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
