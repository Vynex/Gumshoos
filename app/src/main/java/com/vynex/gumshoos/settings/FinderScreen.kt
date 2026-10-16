package com.vynex.gumshoos.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.R
import com.vynex.gumshoos.SearchWidgetFinderProvider
import com.vynex.gumshoos.widget.WidgetBarFill
import com.vynex.gumshoos.widget.WidgetBarLogo
import com.vynex.gumshoos.widget.WidgetBarMetrics
import com.vynex.gumshoos.widget.WidgetBarRenderer
import com.vynex.gumshoos.widget.WidgetBarStyle
import kotlin.math.roundToInt

/** The narrowest the widget gets: two cells. The preview is drawn at this width. */
private val PreviewWidth = 110.dp
private val DefaultPreviewHeight = 60.dp
private val MinPreviewHeight = 48.dp
private val MaxPreviewHeight = 140.dp

/**
 * Styling for the Finder pill.
 *
 * The same controls as the search bar's screen less everything about actions and the horizontal
 * margin, with values of its own. Every change writes config and re-pushes the placed widgets.
 */
@Composable
internal fun FinderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val style = GumshoosConfig.finderStyle

    fun push() = SearchWidgetFinderProvider.refreshAll(context)

    var paletteOpen by remember { mutableStateOf(false) }
    var accentOpen by remember { mutableStateOf(false) }

    SettingsScaffoldWithHeader(
        title = "Finder widget",
        onBack = onBack,
        header = { FinderPreview(style) },
        actions = {
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(R.drawable.ic_more_vertical), contentDescription = "More options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val extended = GumshoosConfig.finderExtendedSpacing
                DropdownMenuItem(
                    text = {
                        Text(
                            if (extended) {
                                "Show less values for spacings"
                            } else {
                                "Show more values for spacings"
                            },
                        )
                    },
                    onClick = {
                        menuOpen = false
                        GumshoosConfig.setFinderExtendedSpacing(context, !extended)
                        push()
                    },
                )
            }
        },
    ) {
        ColorPickerAccordion(
            currentColor = WidgetBarRenderer.resolveFill(context, style.fill),
            selected = (style.fill as? WidgetBarFill.Solid)?.color,
            adaptive = GumshoosConfig.finderFillAdaptive,
            onAdaptiveChange = { adaptive ->
                GumshoosConfig.setFinderFillAdaptive(context, adaptive)
                push()
            },
            expanded = paletteOpen,
            onExpandedChange = { paletteOpen = it },
            onPick = { picked ->
                GumshoosConfig.setFinderSolidColor(context, picked)
                push()
            },
            title = "Background colour",
            modifier = Modifier.padding(vertical = 8.dp),
        )

        ColorPickerAccordion(
            currentColor = WidgetBarRenderer.resolveAccent(context, style.accent),
            selected = (style.accent as? WidgetBarFill.Solid)?.color,
            adaptive = GumshoosConfig.finderAccentAdaptive,
            onAdaptiveChange = { adaptive ->
                GumshoosConfig.setFinderAccentAdaptive(context, adaptive)
                push()
            },
            expanded = accentOpen,
            onExpandedChange = { accentOpen = it },
            onPick = { picked ->
                GumshoosConfig.setFinderAccentColor(context, picked)
                push()
            },
            title = "Accent colour",
            modifier = Modifier.padding(bottom = 8.dp),
        )

        SettingsGroup(title = "Style") {
            SettingsSliderRow(
                label = "Opacity",
                value = GumshoosConfig.finderFillAlpha,
                min = 0,
                max = 100,
                step = 5,
                valueText = { "$it%" },
                onValue = { GumshoosConfig.setFinderFillAlpha(context, it) },
                onValueFinished = ::push,
            )
            SettingsSwitchRow(
                title = "Outline only",
                description = "Draws just a border without filling it.",
                checked = style.wireframe,
                onCheckedChange = { GumshoosConfig.setFinderWireframe(context, it); push() },
            )
            SettingsSwitchRow(
                title = "Shadow",
                description = "A short drop shadow. This also slightly reduces the widget size.",
                checked = style.shadow,
                onCheckedChange = { GumshoosConfig.setFinderShadow(context, it); push() },
            )
        }

        SettingsGroup(title = "Shape") {
            SettingsSliderRow(
                label = "Corners",
                value = GumshoosConfig.finderCorner,
                min = 0,
                max = 100,
                step = 10,
                valueText = ::finderCornerLabel,
                onValue = { GumshoosConfig.setFinderCorner(context, it) },
                onValueFinished = ::push,
            )
        }

        SettingsGroup(title = "Spacing") {
            SettingsSliderRow(
                label = "Horizontal margin",
                value = GumshoosConfig.finderPaddingH,
                min = 0,
                max = WidgetBarMetrics.MAX_PADDING_H_DP,
                step = 2,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setFinderPaddingH(context, it) },
                onValueFinished = ::push,
            )
            SettingsSliderRow(
                label = "Vertical margin",
                value = GumshoosConfig.finderPaddingV,
                min = 0,
                max = WidgetBarMetrics.maxPaddingVDp(GumshoosConfig.finderExtendedSpacing),
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setFinderPaddingV(context, it) },
                onValueFinished = ::push,
            )
            SettingsSliderRow(
                label = "Horizontal padding",
                value = GumshoosConfig.finderIconPadding,
                min = 0,
                max = WidgetBarMetrics.MAX_ICON_PADDING_DP,
                step = 2,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setFinderIconPadding(context, it) },
                onValueFinished = ::push,
            )
        }

        SettingsGroup(title = "Logo and text") {
            WidgetBarLogo.entries.forEach { logo ->
                SettingsRadioRow(
                    title = logo.label,
                    description = if (logo.accent) {
                        "Follows the accent colour."
                    } else if (logo.dynamic) {
                        "Uses your wallpaper's colours."
                    } else if (logo.tintable) {
                        "Follows the widget's contrast colour."
                    } else {
                        "Keeps its own colours."
                    },
                    selected = style.logo == logo,
                    onClick = { GumshoosConfig.setFinderLogo(context, logo); push() },
                    icon = if (logo.dynamic) null else logo.icon,
                    iconBitmap = if (logo.dynamic) logoBitmap(logo) else null,
                    tintIcon = logo.tintable || logo.accent,
                )
            }
            SettingsGroupDivider()
            SettingsSliderRow(
                label = "Logo size",
                value = GumshoosConfig.finderLogoSize,
                min = WidgetBarMetrics.MIN_ICON_SIZE_DP,
                max = WidgetBarMetrics.MAX_ICON_SIZE_DP,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setFinderLogoSize(context, it) },
                onValueFinished = ::push,
            )
            SettingsSliderRow(
                label = "Logo and text gap",
                value = GumshoosConfig.finderGap,
                min = 0,
                max = WidgetBarMetrics.MAX_FINDER_GAP_DP,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setFinderGap(context, it) },
                onValueFinished = ::push,
            )
            SettingsSliderRow(
                label = "Text size",
                value = GumshoosConfig.finderTextSize,
                min = WidgetBarMetrics.MIN_FINDER_TEXT_SP,
                max = WidgetBarMetrics.MAX_FINDER_TEXT_SP,
                valueText = { "${it}sp" },
                onValue = { GumshoosConfig.setFinderTextSize(context, it) },
                onValueFinished = ::push,
            )
            SettingsSwitchRow(
                title = "Show \"Finder\"",
                description = "The text next to the logo.",
                checked = style.showHint,
                onCheckedChange = { GumshoosConfig.setFinderShowHint(context, it); push() },
            )
        }

        SettingsDivider()

        SettingsGroup {
            SettingsActionRow(
                title = "Reset to defaults",
                summary = "Colour, shape and logo go back to how they started.",
                icon = R.drawable.ic_provider_rotate_ccw,
                onClick = {
                    GumshoosConfig.resetFinder(context)
                    push()
                },
            )
        }
    }
}

private fun finderCornerLabel(percent: Int): String = when {
    percent <= 5 -> "Square"
    percent >= 95 -> "Pill"
    else -> "$percent%"
}

/**
 * The pill as it will appear, at its narrowest.
 *
 * The background is [WidgetBarRenderer.toBitmap] — the widget's own call — rasterised 1:1 for the
 * reasons given on the search bar's preview.
 */
@Composable
private fun FinderPreview(style: WidgetBarStyle) {
    val context = LocalContext.current
    val previewHeight = remember {
        placedWidgetHeight(
            context, SearchWidgetFinderProvider::class.java, MinPreviewHeight, MaxPreviewHeight,
        ) ?: DefaultPreviewHeight
    }
    val density = LocalDensity.current.density
    val gap = GumshoosConfig.finderGap.dp
    val logoSize = dimensionResource(R.dimen.widget_bar_logo_size) +
        (style.logoSizeDp - WidgetBarMetrics.DEFAULT_LOGO_SIZE_DP).dp
    val resolved = WidgetBarRenderer.resolveFill(context, style.fill)
    val content = Color(WidgetBarMetrics.contentColor(resolved, style))
    val accent = WidgetBarRenderer.resolveAccent(context, style.accent)
    val logoBitmap = logoBitmap(style.logo)
    val logoPainter = remember(logoBitmap) { BitmapPainter(logoBitmap) }

    WallpaperBackdrop(
        Modifier
            .fillMaxWidth()
            .height(previewHeight + PreviewPadding * 2),
    ) {
        BoxWithConstraints(
            Modifier
                .width(PreviewWidth)
                .align(Alignment.Center)
                .height(previewHeight),
        ) {
            val scale = WidgetBarMetrics.rasterScale(constraints.maxWidth, constraints.maxHeight)
            val previewDensity = density * scale
            val wPx = (constraints.maxWidth * scale).roundToInt()
            val hPx = (constraints.maxHeight * scale).roundToInt()

            val background = remember(style, wPx, hPx, resolved) {
                WidgetBarRenderer.toBitmap(context, style, wPx, hPx, previewDensity).asImageBitmap()
            }
            Image(
                bitmap = background,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal =
                            (style.iconPaddingDp + WidgetBarMetrics.insetHDp(style)).dp,
                    ),
            ) {
                Image(
                    painter = if (style.logo.dynamic) {
                        logoPainter
                    } else {
                        painterResource(style.logo.icon)
                    },
                    contentDescription = null,
                    colorFilter = WidgetBarMetrics.logoTint(style.logo, content.toArgb(), accent)
                        ?.let { ColorFilter.tint(Color(it)) },
                    modifier = Modifier.size(logoSize),
                )
                if (style.showHint) {
                    Spacer(Modifier.width(gap))
                    Text(
                        text = stringResource(R.string.widget_finder_hint),
                        color = content,
                        fontSize = GumshoosConfig.finderTextSize.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
