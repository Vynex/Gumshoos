package com.vynex.gumshoos.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.R
import com.vynex.gumshoos.SearchProviders
import com.vynex.gumshoos.SearchWidgetProvider
import com.vynex.gumshoos.WeatherRepository
import com.vynex.gumshoos.WidgetActionTargets
import com.vynex.gumshoos.widget.WeatherIcons
import com.vynex.gumshoos.widget.WidgetAction
import com.vynex.gumshoos.widget.SlotKind
import com.vynex.gumshoos.widget.WidgetBarFill
import com.vynex.gumshoos.widget.WidgetBarLogo
import com.vynex.gumshoos.widget.WidgetBarMetrics
import com.vynex.gumshoos.widget.WidgetBarRenderer
import com.vynex.gumshoos.widget.WidgetBarStyle
import com.vynex.gumshoos.widget.WidgetDateFormat
import sh.calvin.reorderable.ReorderableColumn
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * A logo drawn through the framework, for the ones whose colours come from the palette.
 *
 * Keyed on the configuration, which changes with night mode and with the wallpaper's colours, so
 * the bitmap is redrawn when the palette moves rather than kept from the one it was first made in.
 */
@Composable
private fun logoBitmap(logo: WidgetBarLogo): ImageBitmap {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(logo, configuration) {
        val drawable = requireNotNull(ContextCompat.getDrawable(context, logo.icon)).mutate()
        drawable.toBitmap(LOGO_BITMAP_PX, LOGO_BITMAP_PX).asImageBitmap()
    }
}

private const val LOGO_BITMAP_PX = 128

/** Fallback preview height, used when no bar is placed yet to measure. */
private val DefaultPreviewHeight = 60.dp

/** Clamp on a measured cell, so an odd report can't produce a preview the screen can't hold. */
private val MinPreviewHeight = 48.dp
private val MaxPreviewHeight = 140.dp

/**
 * Styling for the wide search bar.
 *
 * Every change writes config and immediately re-pushes the placed widgets, so the home screen and
 * this screen stay in step — the setters deliberately don't do that themselves, matching how the
 * app icon widget's tuning works.
 */
@Composable
internal fun WidgetBarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val style = GumshoosConfig.widgetBarStyle

    // Which actions this phone can run, re-read whenever the screen comes back to the front.
    //
    // Installing an app happens in the Play Store, which we send the user to and get no result back
    // from — the same shape as a permission granted in system settings, so it uses the same helper.
    // A plain read during composition is taken once and never again: the Uninstalled group then
    // survives the install that was made from it, which is exactly how this was first shipped.
    //
    // Threaded down rather than read again inside the preview and the rows. Two independent reads
    // would drift apart for one recomposition, and the preview would promise an icon the list says
    // is unavailable.
    val available = rememberOnResume {
        WidgetAction.entries.filter { WidgetActionTargets.isAvailable(context, it) }.toSet()
    }

    fun push() = SearchWidgetProvider.refreshAll(context)

    var paletteOpen by remember { mutableStateOf(false) }
    var accentOpen by remember { mutableStateOf(false) }

    SettingsScaffoldWithHeader(
        title = "Search bar widget",
        onBack = onBack,
        header = { WidgetBarPreview(style, available) },
    ) {
        ColorPickerAccordion(
            currentColor = WidgetBarRenderer.resolveFill(context, style.fill),
            selected = (style.fill as? WidgetBarFill.Solid)?.color,
            adaptive = GumshoosConfig.widgetBarFillAdaptive,
            onAdaptiveChange = { adaptive ->
                GumshoosConfig.setWidgetBarFillAdaptive(context, adaptive)
                push()
            },
            expanded = paletteOpen,
            onExpandedChange = { paletteOpen = it },
            onPick = { picked ->
                GumshoosConfig.setWidgetBarSolidColor(context, picked)
                push()
            },
            title = "Background colour",
            modifier = Modifier.padding(vertical = 8.dp),
        )

        ColorPickerAccordion(
            currentColor = WidgetBarRenderer.resolveAccent(context, style.accent),
            selected = (style.accent as? WidgetBarFill.Solid)?.color,
            adaptive = GumshoosConfig.widgetBarAccentAdaptive,
            onAdaptiveChange = { adaptive ->
                GumshoosConfig.setWidgetBarAccentAdaptive(context, adaptive)
                push()
            },
            expanded = accentOpen,
            onExpandedChange = { accentOpen = it },
            onPick = { picked ->
                GumshoosConfig.setWidgetBarAccentColor(context, picked)
                push()
            },
            title = "Accent colour",
            modifier = Modifier.padding(bottom = 8.dp),
        )

        SettingsGroup(title = "Style") {
            SettingsSliderRow(
                label = "Opacity",
                value = GumshoosConfig.widgetBarFillAlpha,
                min = 0,
                max = 100,
                // Stepped, so a drag redraws the preview twenty times rather than once per frame.
                step = 5,
                valueText = { "$it%" },
                onValue = { GumshoosConfig.setWidgetBarFillAlpha(context, it) },
                onValueFinished = ::push,
            )
            SettingsSwitchRow(
                title = "Outline only",
                description = "Draws just a border without filling it.",
                checked = style.wireframe,
                onCheckedChange = { GumshoosConfig.setWidgetBarWireframe(context, it); push() },
            )
            SettingsSwitchRow(
                title = "Shadow",
                description = "A short drop shadow. This also slightly reduces the widget size.",
                checked = style.shadow,
                onCheckedChange = { GumshoosConfig.setWidgetBarShadow(context, it); push() },
            )
        }

        SettingsGroup(title = "Shape") {
            SettingsSliderRow(
                label = "Corners",
                value = GumshoosConfig.widgetBarCorner,
                min = 0,
                max = 100,
                step = 10,
                valueText = ::cornerLabel,
                onValue = { GumshoosConfig.setWidgetBarCorner(context, it) },
                onValueFinished = ::push,
            )
        }

        // Their own group because the two only mean anything read against each other: they are the
        // bar's size, expressed as what is left around it.
        SettingsGroup(title = "Spacing") {
            SettingsSliderRow(
                label = "Horizontal margin",
//                summary = "0 makes the bar span the whole cell.",
                value = GumshoosConfig.widgetBarPaddingH,
                min = 0,
                max = WidgetBarMetrics.MAX_PADDING_H_DP,
                // Coarser than the vertical one. It runs to 32dp, where single steps are more
                // notches than the eye can tell apart and a drag that has to cross all of them.
                step = 2,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setWidgetBarPaddingH(context, it) },
                onValueFinished = ::push,
            )
            SettingsSliderRow(
                label = "Vertical margin",
//                summary = "How thick the bar is within its row.",
                value = GumshoosConfig.widgetBarPaddingV,
                min = 0,
                max = WidgetBarMetrics.MAX_PADDING_V_DP,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setWidgetBarPaddingV(context, it) },
                onValueFinished = ::push,
            )
            SettingsSliderRow(
                label = "Horizontal padding",
                value = GumshoosConfig.widgetBarIconPadding,
                min = 0,
                max = WidgetBarMetrics.MAX_ICON_PADDING_DP,
                step = 2,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setWidgetBarIconPadding(context, it) },
                onValueFinished = ::push,
            )
        }

        SettingsGroup(title = "Icons") {
            SettingsSliderRow(
                label = "Logo size",
                value = GumshoosConfig.widgetBarLogoSize,
                min = WidgetBarMetrics.MIN_ICON_SIZE_DP,
                max = WidgetBarMetrics.MAX_ICON_SIZE_DP,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setWidgetBarLogoSize(context, it) },
                onValueFinished = ::push,
            )
            SettingsSliderRow(
                label = "Action icon size",
                value = GumshoosConfig.widgetBarIconSize,
                min = WidgetBarMetrics.MIN_ICON_SIZE_DP,
                max = WidgetBarMetrics.MAX_ICON_SIZE_DP,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setWidgetBarIconSize(context, it) },
                onValueFinished = ::push,
            )
            SettingsSliderRow(
                label = "Action spacing",
                value = GumshoosConfig.widgetBarActionSpacing,
                min = 0,
                max = WidgetBarMetrics.MAX_ACTION_SPACING_DP,
                step = 2,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setWidgetBarActionSpacing(context, it) },
                onValueFinished = ::push,
            )
            SettingsSwitchRow(
                title = "Themed actions",
                description = "Draws the action icons in the accent colour.",
                checked = style.themedActions,
                onCheckedChange = { GumshoosConfig.setWidgetBarThemedActions(context, it); push() },
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
                        "Follows the bar's contrast colour."
                    } else {
                        "Keeps its own colours."
                    },
                    selected = style.logo == logo,
                    onClick = { GumshoosConfig.setWidgetBarLogo(context, logo); push() },
                    icon = if (logo.dynamic) null else logo.icon,
                    iconBitmap = if (logo.dynamic) logoBitmap(logo) else null,
                    tintIcon = logo.tintable || logo.accent,
                )
            }
            SettingsGroupDivider()
            SettingsSwitchRow(
                title = "Show \"Search\"",
                description = "The placeholder text next to the logo.",
                checked = style.showHint,
                onCheckedChange = { GumshoosConfig.setWidgetBarShowHint(context, it); push() },
            )
        }

        ActionGroups(available = available, onChanged = ::push)

        SettingsDivider()

        SettingsGroup {
            SettingsActionRow(
                title = "Reset to defaults",
                summary = "Colour, shape, logo and actions go back to how they started.",
                icon = R.drawable.ic_provider_rotate_ccw,
                onClick = {
                    GumshoosConfig.resetWidgetBar(context)
                    push()
                },
            )
        }
    }
}

/**
 * The bar as it will actually appear.
 *
 * The background comes from [WidgetBarRenderer.toBitmap] — the same call the widget makes, not an
 * imitation of it. Drawing into Compose's canvas instead would be hardware-accelerated, where
 * `setShadowLayer` does nothing for shapes: the preview would lose its shadow while the widget kept
 * one, and the mismatch would look like a widget bug.
 *
 * The content layer is laid out in Compose against the same dimens the RemoteViews layout uses.
 */
@Composable
private fun WidgetBarPreview(style: WidgetBarStyle, available: Set<WidgetAction>) {
    val context = LocalContext.current
    // Drawn at the height of a bar you have actually placed, rather than a guess. Margins are in
    // dp, so their effect depends entirely on how tall the cell is — a fixed-height preview makes
    // an 8dp margin look generous here and thin on a tall launcher row.
    val previewHeight = remember {
        placedWidgetHeight(
            context, SearchWidgetProvider::class.java, MinPreviewHeight, MaxPreviewHeight,
        ) ?: DefaultPreviewHeight
    }
    val density = LocalDensity.current.density
    val gap = dimensionResource(R.dimen.widget_bar_gap)
    val locale = LocalConfiguration.current.locales[0]
    val logoSize = dimensionResource(R.dimen.widget_bar_logo_size) +
        (style.logoSizeDp - WidgetBarMetrics.DEFAULT_LOGO_SIZE_DP).dp
    val actionSize = style.iconSizeDp.dp

    val resolved = WidgetBarRenderer.resolveFill(context, style.fill)
    val content = Color(WidgetBarMetrics.contentColor(resolved, style))
    // The text keeps [content]; the accent is for themed actions and the solid logo.
    val accent = WidgetBarRenderer.resolveAccent(context, style.accent)
    val logoBitmap = logoBitmap(style.logo)
    val logoPainter = remember(logoBitmap) { BitmapPainter(logoBitmap) }

    // Filtered by availability exactly as the widget filters it, or the preview would promise an
    // icon the bar on the home screen doesn't draw.
    val actions = SearchProviders.inConfiguredOrder(
        WidgetAction.entries.filter { it.id in GumshoosConfig.widgetBarActions && it in available },
        GumshoosConfig.widgetBarActionOrder,
        WidgetAction::id,
    )

    WallpaperBackdrop(
        Modifier
            .fillMaxWidth()
            .height(previewHeight + PreviewPadding * 2),
    ) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .align(Alignment.Center)
            .padding(horizontal = PreviewPadding)
            .height(previewHeight),
    ) {
        // Rasterised at the exact pixel size this box occupies, so the bitmap lands 1:1 on the
        // screen and nothing resamples it.
        //
        // It used to cap the density at 2x and let ContentScale stretch the result back up, to hold
        // down the allocation while a slider is dragged. That is a 1.5x bilinear upscale on a 3x
        // phone, and the shadow is the one part of the drawing fine enough to show it: its gradient
        // is a few pixels wide, so the interpolation facets it and the rounded corners come out
        // visibly stepped. The widget itself never looked like that — it rasterises at full density
        // — so the preview was showing a defect that only the preview had, which is the one thing a
        // preview must not do. Every slider here is stepped and the bitmap is remembered against the
        // style, so the cost is one bitmap per notch crossed, not one per frame.
        //
        // Past the area budget both the pixel size and the density fall together, which keeps it a
        // uniform scale of the very same drawing rather than a different one — the shadow and the
        // margins stay in proportion because they are derived from the density passed in.
        val scale = WidgetBarMetrics.rasterScale(constraints.maxWidth, constraints.maxHeight)
        val previewDensity = density * scale
        val wPx = (constraints.maxWidth * scale).roundToInt()
        val hPx = (constraints.maxHeight * scale).roundToInt()

        // Keyed on everything that changes the pixels, so typing in the hex field redraws but
        // scrolling the screen doesn't re-rasterise on every frame.
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
            modifier = Modifier
                .fillMaxSize()
                // The bar's real edge, not the configured margin: a shadow widens it, and the logo
                // has to sit inside whatever the renderer drew — the same read the widget makes.
                .padding(horizontal = (style.iconPaddingDp + WidgetBarMetrics.insetHDp(style)).dp),
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
                    text = stringResource(R.string.widget_search_hint),
                    color = content,
                    fontSize = 16.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            // Walked in order, drawing whichever half of the slot the action there needs — the same
            // decision `decorate` makes per slot, so the date lands wherever it was dragged to
            // rather than always at the end.
            actions.take(WidgetAction.SLOT_COUNT).forEach { action ->
                Spacer(Modifier.width(gap + (style.actionSpacingDp - WidgetBarMetrics.DEFAULT_ACTION_SPACING_DP).dp))
                if (action.kind == SlotKind.TEXT) {
                    // Temperature then mark, matching the layout's order — the widget shows both,
                    // and a preview that drew only the text was the reason this looked different
                    // on the home screen from how it looked here.
                    Text(
                        text = WeatherRepository.displayText() ?: WeatherRepository.STALE_TEXT,
                        color = content,
                        fontSize = 14.sp,
                    )
                    WeatherIcons.forCode(WeatherRepository.conditionCode())?.let { mark ->
                        // The tighter pair gap, not the slot gap — same override the widget makes.
                        Spacer(Modifier.width(dimensionResource(R.dimen.widget_bar_pair_gap)))
                        Image(
                            painter = painterResource(mark),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(
                                Color(
                                    WidgetBarMetrics.actionTint(style, true, content.toArgb(), accent)
                                        ?: content.toArgb(),
                                ),
                            ),
                            modifier = Modifier.size(actionSize),
                        )
                    }
                } else if (action.kind == SlotKind.CLOCK) {
                    // Formatted once here rather than ticking: this is a still of the bar, and the
                    // real one keeps itself current through its TextClock. Same pattern source, so
                    // the two agree about where the locale puts the day.
                    Text(
                        text = remember(locale) {
                            SimpleDateFormat(WidgetDateFormat.patternFor(locale), locale)
                                .format(Date())
                        },
                        color = content,
                        fontSize = 14.sp,
                    )
                } else {
                    Image(
                        painter = painterResource(action.icon),
                        contentDescription = null,
                        colorFilter = WidgetBarMetrics
                            .actionTint(style, action.tintable, content.toArgb(), accent)
                            ?.let { ColorFilter.tint(Color(it)) },
                        // scale, not a smaller size: the widget shrinks the artwork inside a box
                        // that stays put, so scaling the box would move the bar's spacing with it.
                        modifier = Modifier.size(actionSize).scale(action.iconScale),
                    )
                }
            }
        }
    }
    }
}

/** The two ends read as shapes rather than percentages; between them the number says it. */
private fun cornerLabel(percent: Int): String = when {
    percent <= 5 -> "Square"
    percent >= 95 -> "Pill"
    else -> "$percent%"
}

@Composable
private fun ActionGroups(available: Set<WidgetAction>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val all = SearchProviders.inConfiguredOrder(
        WidgetAction.entries.toList(),
        GumshoosConfig.widgetBarActionOrder,
        WidgetAction::id,
    )

    // Split rather than dimmed in place. An action whose app is missing isn't a choice the bar can
    // offer — there is no fallback behind Lens, so a greyed checkbox would be inviting a tap that
    // could never do anything. It moves to its own group with the one action that helps: install it.
    val (installed, missing) = all.partition { it in available }
    val enabledCount = installed.count { it.id in GumshoosConfig.widgetBarActions }

    // A move reports positions among the rows on screen, which are only the installed ones, while
    // the order saved covers every action. An uninstalled action keeps its place in that saved
    // list, so installing its app returns it to where it was rather than to the end.
    //
    // Both the drag and the two accessibility actions land here. When they didn't, "move down" past
    // an uninstalled neighbour swapped with a row that isn't on screen and appeared to do nothing.
    var weatherOpen by remember { mutableStateOf(false) }
    if (weatherOpen) {
        WeatherSettingsDialog(
            onDismiss = { refetch ->
                weatherOpen = false
                // Only when a key or a place actually moved. Switching Celsius to Fahrenheit
                // reports false and costs no request at all — the cached reading already answers
                // it, and the redraw below is what makes the change visible.
                if (refetch) WeatherRepository.refreshIfDue(context)
                onChanged()
            },
        )
    }

    fun settle(from: Int, to: Int) {
        GumshoosConfig.setWidgetBarActionOrder(
            context,
            SearchProviders.reorderVisible(
                all.map { it.id }, installed.map { it.id }, from, to,
            ),
        )
        onChanged()
    }

    SettingsGroup(
        title = "Actions",
        note = "Tappable actions at the right of the widget, in the order shown. " +
            "Drag a row by its handle to rearrange them. Rest of the bar still opens search.",
    ) {
        ReorderableColumn(
            list = installed,
            onSettle = ::settle,
            modifier = Modifier.fillMaxWidth(),
        ) { index, action, isDragging ->
            // The item wrapper is what carries the row's drag offset and hands its content the
            // scope `draggableHandle` lives in; the row cannot reach it from the column lambda.
            ReorderableItem {
                val checked = action.id in GumshoosConfig.widgetBarActions
                // Weather cannot be switched on without a key: with none, every fetch returns a
                // 401 and the slot would sit on a dash forever with nothing saying why. The gear
                // is where the key goes, so the row still leads somewhere useful while disabled.
                val needsKey = action == WidgetAction.WEATHER && !WeatherRepository.isConfigured()
                ProviderRow(
                    label = action.label,
                    icon = action.icon,
                    description = action.description,
                    checked = checked,
                    // Both gates are one-way: they stop a row being switched **on**, never off.
                    // A checked row is therefore always live, or an action that became invalid
                    // after being enabled — weather whose key was cleared, or a slot count that
                    // shrank — would be stuck on with the control that turns it off greyed out.
                    // Room runs out at SLOT_COUNT, and weather with no key can only ever draw a
                    // dash; the rows that would not fit say so by going flat rather than accepting
                    // a tap that silently does nothing.
                    enabled = checked || (!needsKey && enabledCount < WidgetAction.SLOT_COUNT),
                    canMoveUp = index > 0,
                    canMoveDown = index < installed.lastIndex,
                    onCheckedChange = {
                        GumshoosConfig.setWidgetBarActionEnabled(context, action.id, it)
                        onChanged()
                    },
                    onMoveUp = { settle(index, index - 1) },
                    onMoveDown = { settle(index, index + 1) },
                    modifier = Modifier
                        .draggedRow(isDragging)
                        .padding(horizontal = SettingsInset),
                    control = RowControl.CHECKBOX,
                    dragHandle = Modifier.draggableHandle(),
                    onConfigure = if (action == WidgetAction.WEATHER) {
                        { weatherOpen = true }
                    } else {
                        null
                    },
                )
            }
        }

    }

    // A group of its own rather than a quieter heading inside the one above. These are not further
    // actions to arrange, they are actions this phone cannot offer at all.
    if (missing.isNotEmpty()) {
        SettingsGroup(
            title = "Uninstalled",
            note = "These need an app that isn't on this phone.",
        ) {
            missing.forEach { action ->
                action.requiredPackage?.let {
                    MissingAppRow(label = action.label, icon = action.icon, packageName = it)
                }
            }
        }
    }
}
