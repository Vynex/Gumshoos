package com.vynex.gumshoos.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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

/**
 * A sectioned colour picker.
 *
 * The wallpaper section is the point of it — those are the same tones the system derives its own
 * theme from, so a bar coloured from them belongs on the home screen rather than sitting on top of
 * it. They are plain resources rather than a `ColorScheme`, so they don't shift with light/dark and
 * a pick stays what was picked.
 */
@Composable
internal fun ColorPickerSections(
    selected: Int?,
    onPick: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SettingsSection("From your wallpaper")
        WallpaperRow("Accent", accent1Tones(), selected, onPick)
        WallpaperRow("Accent alt", accent2Tones(), selected, onPick)
        WallpaperRow("Accent soft", accent3Tones(), selected, onPick)
        WallpaperRow("Neutral", neutral1Tones(), selected, onPick)
        WallpaperRow("Neutral alt", neutral2Tones(), selected, onPick)

        SettingsSection("App theme")
        val scheme = MaterialTheme.colorScheme
        SwatchRow(
            listOf(
                scheme.primary, scheme.primaryContainer, scheme.secondary,
                scheme.secondaryContainer, scheme.tertiary, scheme.surface,
                scheme.surfaceVariant, scheme.outline,
            ),
            selected,
            onPick,
        )

        SettingsSection("Neutrals")
        SwatchRow(NEUTRALS, selected, onPick)

        SettingsSection("Custom")
        HexField(selected, onPick)
    }
}

@Composable
private fun WallpaperRow(
    label: String,
    tones: List<Color>,
    selected: Int?,
    onPick: (Int) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    SwatchRow(tones, selected, onPick)
}

@Composable
private fun SwatchRow(colors: List<Color>, selected: Int?, onPick: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        colors.forEach { color -> Swatch(color, selected, onPick) }
    }
}

@Composable
private fun Swatch(color: Color, selected: Int?, onPick: (Int) -> Unit) {
    val argb = color.toArgb()
    // Compared on RGB only: the stored colour carries no alpha, and the opacity slider owns that.
    val isSelected = selected != null && (selected and 0xFFFFFF) == (argb and 0xFFFFFF)
    SwatchCircle(
        color = color,
        isSelected = isSelected,
        onClick = { onPick(argb) },
    )
}

@Composable
private fun SwatchCircle(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        if (isSelected) {
            // Same luminance rule the bar uses for its own content, so the tick is legible on a
            // swatch of any brightness rather than vanishing into the pale end of a ramp.
            val tick = if (WidgetBarMetrics.isLight(color.toArgb())) Color.Black else Color.White
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = tick,
                modifier = Modifier.size(20.dp),
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
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
        )
    }
    Spacer(Modifier.height(4.dp))
}

/*
 * Seven tones out of each thirteen-tone ramp. All thirteen is 65 swatches and a wall of
 * near-identical pale steps; these are the ones far enough apart to tell apart at 44dp.
 */

@Composable
private fun accent1Tones(): List<Color> = listOf(
    colorResource(android.R.color.system_accent1_100),
    colorResource(android.R.color.system_accent1_200),
    colorResource(android.R.color.system_accent1_400),
    colorResource(android.R.color.system_accent1_500),
    colorResource(android.R.color.system_accent1_600),
    colorResource(android.R.color.system_accent1_700),
    colorResource(android.R.color.system_accent1_900),
)

@Composable
private fun accent2Tones(): List<Color> = listOf(
    colorResource(android.R.color.system_accent2_100),
    colorResource(android.R.color.system_accent2_200),
    colorResource(android.R.color.system_accent2_400),
    colorResource(android.R.color.system_accent2_500),
    colorResource(android.R.color.system_accent2_600),
    colorResource(android.R.color.system_accent2_700),
    colorResource(android.R.color.system_accent2_900),
)

@Composable
private fun accent3Tones(): List<Color> = listOf(
    colorResource(android.R.color.system_accent3_100),
    colorResource(android.R.color.system_accent3_200),
    colorResource(android.R.color.system_accent3_400),
    colorResource(android.R.color.system_accent3_500),
    colorResource(android.R.color.system_accent3_600),
    colorResource(android.R.color.system_accent3_700),
    colorResource(android.R.color.system_accent3_900),
)

@Composable
private fun neutral1Tones(): List<Color> = listOf(
    colorResource(android.R.color.system_neutral1_50),
    colorResource(android.R.color.system_neutral1_100),
    colorResource(android.R.color.system_neutral1_200),
    colorResource(android.R.color.system_neutral1_500),
    colorResource(android.R.color.system_neutral1_700),
    colorResource(android.R.color.system_neutral1_800),
    colorResource(android.R.color.system_neutral1_900),
)

@Composable
private fun neutral2Tones(): List<Color> = listOf(
    colorResource(android.R.color.system_neutral2_50),
    colorResource(android.R.color.system_neutral2_100),
    colorResource(android.R.color.system_neutral2_200),
    colorResource(android.R.color.system_neutral2_500),
    colorResource(android.R.color.system_neutral2_700),
    colorResource(android.R.color.system_neutral2_800),
    colorResource(android.R.color.system_neutral2_900),
)

private val NEUTRALS = listOf(
    Color(0xFF000000), Color(0xFF1B1B1B), Color(0xFF3C3C3C), Color(0xFF6E6E6E),
    Color(0xFF9E9E9E), Color(0xFFCFCFCF), Color(0xFFE8E8E8), Color(0xFFFFFFFF),
)
