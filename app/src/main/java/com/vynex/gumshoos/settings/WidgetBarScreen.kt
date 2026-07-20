package com.vynex.gumshoos.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.R
import com.vynex.gumshoos.SearchProviders
import com.vynex.gumshoos.SearchWidgetProvider
import com.vynex.gumshoos.widget.WidgetAction
import com.vynex.gumshoos.widget.WidgetBarFill
import com.vynex.gumshoos.widget.WidgetBarLogo
import com.vynex.gumshoos.widget.WidgetBarMetrics
import com.vynex.gumshoos.widget.WidgetBarRenderer
import com.vynex.gumshoos.widget.WidgetBarStyle
import kotlin.math.roundToInt

/** Fallback preview height, used when no bar is placed yet to measure. */
private val DefaultPreviewHeight = 60.dp

/** Clamp on a measured cell, so an odd report can't produce a preview the screen can't hold. */
private val MinPreviewHeight = 48.dp
private val MaxPreviewHeight = 140.dp

/** Rasterisation ceiling for the preview only — see the note in WidgetBarPreview. */
private const val PreviewMaxDensity = 2f

/**
 * Wallpaper visible around the bar in the preview.
 *
 * Some is the point: a bar drawn edge to edge against the backdrop shows nothing about how it sits
 * on a home screen, which is what the margin and opacity settings are for.
 */
private val PreviewPadding = 16.dp

/**
 * Styling for the wide search bar.
 *
 * Every change writes config and immediately re-pushes the placed widgets, so the home screen and
 * this screen stay in step — the setters deliberately don't do that themselves, matching how the
 * 1x1's tuning works.
 */
@Composable
internal fun WidgetBarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val style = GumshoosConfig.widgetBarStyle

    fun push() = SearchWidgetProvider.refreshAll(context)

    var paletteOpen by remember { mutableStateOf(false) }

    SettingsScaffoldWithHeader(
        title = "Search bar widget",
        onBack = onBack,
        header = { WidgetBarPreview(style) },
    ) {
        SettingsSection("Fill")
        SettingsSwitchRow(
            title = "Outline only",
            description = "Draws just the border, in the fill colour, with nothing behind it.",
            checked = style.wireframe,
            onCheckedChange = { GumshoosConfig.setWidgetBarWireframe(context, it); push() },
        )
        OpacitySlider(onChanged = ::push)

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
        )

        SettingsDivider()
        SettingsSection("Shape")
        CornerSlider(onChanged = ::push)
        PaddingSlider(
            label = "Side margin",
            value = GumshoosConfig.widgetBarPaddingH,
            max = WidgetBarMetrics.MAX_PADDING_H_DP,
            hint = "0 makes the bar span the whole cell.",
            onValue = { GumshoosConfig.setWidgetBarPaddingH(context, it) },
            onChanged = ::push,
        )
        PaddingSlider(
            label = "Top and bottom margin",
            value = GumshoosConfig.widgetBarPaddingV,
            max = WidgetBarMetrics.MAX_PADDING_V_DP,
            hint = "How thick the bar is within its row.",
            onValue = { GumshoosConfig.setWidgetBarPaddingV(context, it) },
            onChanged = ::push,
        )
        SettingsSwitchRow(
            title = "Shadow",
            description = "A short drop shadow. Draws into the margins above, so it doesn't " +
                "change the bar's size.",
            checked = style.shadow,
            onCheckedChange = { GumshoosConfig.setWidgetBarShadow(context, it); push() },
        )

        SettingsDivider()
        SettingsSection("Contents")
        SettingsSwitchRow(
            title = "Show \"Search\"",
            description = "The placeholder text next to the logo.",
            checked = style.showHint,
            onCheckedChange = { GumshoosConfig.setWidgetBarShowHint(context, it); push() },
        )

        Spacer(Modifier.height(8.dp))
        Text("Logo", style = MaterialTheme.typography.bodyMedium)
        WidgetBarLogo.entries.forEach { logo ->
            SettingsRadioRow(
                title = logo.label,
                description = if (logo.tintable) {
                    "Follows the bar's contrast colour."
                } else {
                    "Keeps its own colours."
                },
                selected = style.logo == logo,
                onClick = { GumshoosConfig.setWidgetBarLogo(context, logo); push() },
            )
        }

        SettingsDivider()
        SettingsSection("Actions")
        SettingsNote(
            "Tappable icons at the right of the bar. Up to ${WidgetAction.SLOT_COUNT} fit; the " +
                "rest of the bar still opens search.",
        )
        ActionRows(onChanged = ::push)

        SettingsDivider()
        OutlinedButton(
            onClick = {
                GumshoosConfig.resetWidgetBar(context)
                push()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reset to defaults") }
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
private fun WidgetBarPreview(style: WidgetBarStyle) {
    val context = LocalContext.current
    // Drawn at the height of a bar you have actually placed, rather than a guess. Margins are in
    // dp, so their effect depends entirely on how tall the cell is — a fixed-height preview makes
    // an 8dp margin look generous here and thin on a tall launcher row.
    val previewHeight = remember { placedBarHeight(context) ?: DefaultPreviewHeight }
    val density = LocalDensity.current.density
    val inset = dimensionResource(R.dimen.widget_bar_inset)
    val gap = dimensionResource(R.dimen.widget_bar_gap)
    val logoSize = dimensionResource(R.dimen.widget_bar_logo_size)
    val actionSize = dimensionResource(R.dimen.widget_bar_action_size)

    val resolved = WidgetBarRenderer.resolveFill(context, style.fill)
    val content = Color(WidgetBarMetrics.contentColor(resolved, style))

    val actions = SearchProviders.inConfiguredOrder(
        WidgetAction.entries.filter { it.id in GumshoosConfig.widgetBarActions },
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
        // Rasterised at a capped density, then scaled back up to fill.
        //
        // A slider writes config on every frame, so a full-resolution bitmap here would allocate
        // roughly a megabyte per frame while one is being dragged. Both the pixel size and the
        // density fall together, which makes this a uniform scale of the very same drawing rather
        // than a different one — the shadow and border stay in proportion because they are derived
        // from the density that is passed in.
        val previewDensity = density.coerceAtMost(PreviewMaxDensity)
        val wPx = (maxWidth.value * previewDensity).toInt()
        val hPx = (previewHeight.value * previewDensity).toInt()

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
                .padding(horizontal = inset + style.paddingHDp.dp),
        ) {
            Image(
                painter = painterResource(style.logo.icon),
                contentDescription = null,
                colorFilter = if (style.logo.tintable) ColorFilter.tint(content) else null,
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
            actions.take(WidgetAction.SLOT_COUNT).forEach { action ->
                Spacer(Modifier.width(gap))
                Image(
                    painter = painterResource(action.icon),
                    contentDescription = null,
                    colorFilter = if (action.tintable) ColorFilter.tint(content) else null,
                    modifier = Modifier.size(actionSize),
                )
            }
        }
    }
    }
}

@Composable
private fun CornerSlider(onChanged: () -> Unit) {
    val context = LocalContext.current
    var corner by remember { mutableStateOf(GumshoosConfig.widgetBarCorner.toFloat()) }
    SettingsNote(cornerLabel(corner.roundToInt()))
    Slider(
        value = corner,
        onValueChange = {
            corner = it
            // Written as it moves, not on release: the preview above is the point of this screen.
            GumshoosConfig.setWidgetBarCorner(context, it.roundToInt())
        },
        onValueChangeFinished = onChanged,
        valueRange = 0f..100f,
        steps = 9,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun cornerLabel(percent: Int): String = when {
    percent <= 5 -> "Corners: square"
    percent >= 95 -> "Corners: fully rounded"
    percent < 50 -> "Corners: $percent% — rounded square"
    else -> "Corners: $percent%"
}

@Composable
private fun OpacitySlider(onChanged: () -> Unit) {
    val context = LocalContext.current
    var alpha by remember { mutableStateOf(GumshoosConfig.widgetBarFillAlpha.toFloat()) }
    SettingsNote("Opacity: ${alpha.roundToInt()}%")
    Slider(
        value = alpha,
        onValueChange = {
            alpha = it
            GumshoosConfig.setWidgetBarFillAlpha(context, it.roundToInt())
        },
        onValueChangeFinished = onChanged,
        valueRange = 0f..100f,
        // Stepped, so a drag redraws the preview twenty times rather than once per frame.
        steps = 19,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The height of a placed search bar, if there is one.
 *
 * Null when none is placed, or when the host hasn't reported a size yet — the same pre-layout zero
 * the renderer guards against.
 */
private fun placedBarHeight(context: Context): Dp? {
    val manager = AppWidgetManager.getInstance(context) ?: return null
    val ids = runCatching {
        manager.getAppWidgetIds(ComponentName(context, SearchWidgetProvider::class.java))
    }.getOrNull()?.toList().orEmpty()

    val heightDp = ids.firstNotNullOfOrNull { id ->
        runCatching {
            manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        }.getOrNull()?.takeIf { it > 0 }
    } ?: return null

    val measured: Dp = heightDp.toFloat().dp
    return measured.coerceIn(MinPreviewHeight, MaxPreviewHeight)
}

/** Shared shape for the two margin sliders — one dp step per notch. */
@Composable
private fun PaddingSlider(
    label: String,
    value: Int,
    max: Int,
    hint: String,
    onValue: (Int) -> Unit,
    onChanged: () -> Unit,
) {
    var current by remember(value) { mutableStateOf(value.toFloat()) }
    SettingsNote("$label: ${current.roundToInt()}dp — $hint")
    Slider(
        value = current,
        onValueChange = {
            current = it
            onValue(it.roundToInt())
        },
        onValueChangeFinished = onChanged,
        valueRange = 0f..max.toFloat(),
        steps = max - 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActionRows(onChanged: () -> Unit) {
    val context = LocalContext.current
    val ordered = SearchProviders.inConfiguredOrder(
        WidgetAction.entries.toList(),
        GumshoosConfig.widgetBarActionOrder,
        WidgetAction::id,
    )
    val enabledCount = ordered.count { it.id in GumshoosConfig.widgetBarActions }

    ordered.forEachIndexed { index, action ->
        val checked = action.id in GumshoosConfig.widgetBarActions
        ProviderRow(
            label = action.label,
            icon = action.icon,
            description = action.description,
            checked = checked,
            // Room runs out at SLOT_COUNT, so the rows that would not fit say so by going flat
            // rather than accepting a tap that silently does nothing.
            enabled = checked || enabledCount < WidgetAction.SLOT_COUNT,
            canMoveUp = index > 0,
            canMoveDown = index < ordered.lastIndex,
            onCheckedChange = {
                GumshoosConfig.setWidgetBarActionEnabled(context, action.id, it)
                onChanged()
            },
            onMoveUp = {
                GumshoosConfig.setWidgetBarActionOrder(
                    context, SearchProviders.reorder(ordered.map { it.id }, action.id, -1),
                )
                onChanged()
            },
            onMoveDown = {
                GumshoosConfig.setWidgetBarActionOrder(
                    context, SearchProviders.reorder(ordered.map { it.id }, action.id, 1),
                )
                onChanged()
            },
            control = RowControl.CHECKBOX,
        )
    }
}
