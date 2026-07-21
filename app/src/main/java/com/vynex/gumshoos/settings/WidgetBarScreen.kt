package com.vynex.gumshoos.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.vynex.gumshoos.EventLog
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.R
import com.vynex.gumshoos.SearchProviders
import com.vynex.gumshoos.SearchWidgetProvider
import com.vynex.gumshoos.WidgetActionTargets
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
            modifier = Modifier.padding(vertical = 8.dp),
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
                description = "Draws just the border, in the fill colour, with nothing behind it.",
                checked = style.wireframe,
                onCheckedChange = { GumshoosConfig.setWidgetBarWireframe(context, it); push() },
                fullBleed = false,
            )
            SettingsSwitchRow(
                title = "Shadow",
                description = "A short drop shadow. It needs a little room to fall into, so the " +
                    "bar pulls in where the margins below are smaller than that.",
                checked = style.shadow,
                onCheckedChange = { GumshoosConfig.setWidgetBarShadow(context, it); push() },
                fullBleed = false,
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
                label = "Side margin",
                summary = "0 makes the bar span the whole cell.",
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
                label = "Top and bottom margin",
                summary = "How thick the bar is within its row.",
                value = GumshoosConfig.widgetBarPaddingV,
                min = 0,
                max = WidgetBarMetrics.MAX_PADDING_V_DP,
                valueText = { "${it}dp" },
                onValue = { GumshoosConfig.setWidgetBarPaddingV(context, it) },
                onValueFinished = ::push,
            )
        }

        SettingsGroup(title = "Logo and text") {
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
                    icon = logo.icon,
                    tintIcon = logo.tintable,
                    fullBleed = false,
                )
            }
            SettingsGroupDivider()
            SettingsSwitchRow(
                title = "Show \"Search\"",
                description = "The placeholder text next to the logo.",
                checked = style.showHint,
                onCheckedChange = { GumshoosConfig.setWidgetBarShowHint(context, it); push() },
                fullBleed = false,
            )
        }

        ActionGroups(available = available, onChanged = ::push)

        SettingsGroup {
            SettingsActionRow(
                title = "Reset to defaults",
                summary = "Colour, shape, logo and actions go back to how they started.",
                imageVector = Icons.Filled.Refresh,
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
    val inset = dimensionResource(R.dimen.widget_bar_inset)
    val gap = dimensionResource(R.dimen.widget_bar_gap)
    val logoSize = dimensionResource(R.dimen.widget_bar_logo_size)
    val actionSize = dimensionResource(R.dimen.widget_bar_action_size)

    val resolved = WidgetBarRenderer.resolveFill(context, style.fill)
    val content = Color(WidgetBarMetrics.contentColor(resolved, style))

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
                .padding(horizontal = inset + WidgetBarMetrics.insetHDp(style).dp),
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

    SettingsGroup(
        title = "Actions",
        note = "Tappable icons at the right of the bar. Up to ${WidgetAction.SLOT_COUNT} fit; the " +
            "rest of the bar still opens search.",
    ) {
        installed.forEachIndexed { index, action ->
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
                canMoveDown = index < installed.lastIndex,
                onCheckedChange = {
                    GumshoosConfig.setWidgetBarActionEnabled(context, action.id, it)
                    onChanged()
                },
                // Reordering moves the action within the *whole* saved order, not within the
                // visible subset. A missing action still holds its place in that list, so
                // installing its app returns it to where it was rather than to the end.
                onMoveUp = {
                    GumshoosConfig.setWidgetBarActionOrder(
                        context, SearchProviders.reorder(all.map { it.id }, action.id, -1),
                    )
                    onChanged()
                },
                onMoveDown = {
                    GumshoosConfig.setWidgetBarActionOrder(
                        context, SearchProviders.reorder(all.map { it.id }, action.id, 1),
                    )
                    onChanged()
                },
                modifier = Modifier.padding(horizontal = SettingsInset),
                control = RowControl.CHECKBOX,
            )
        }
    }

    // A group of its own rather than a quieter heading inside the one above. These are not further
    // actions to arrange, they are actions this phone cannot offer at all.
    if (missing.isNotEmpty()) {
        SettingsGroup(
            title = "Uninstalled",
            note = "These need an app that isn't on this phone.",
        ) {
            missing.forEach { MissingActionRow(it) }
        }
    }
}

/**
 * An action whose app isn't installed: the mark, the name, and a way to get it.
 *
 * No checkbox and no arrows. Both would be controls over something that cannot appear in the bar,
 * and a disabled control reads as "not now" when the honest message is "not without the app".
 *
 * Nothing here is dimmed. The row isn't a switched-off setting, it is an app you don't have — and
 * greying the name says "unavailable to you" where the row's whole purpose is to offer it. The
 * package name is deliberately absent for the same reason: it explains nothing to anyone who
 * wouldn't already know.
 */
@Composable
private fun MissingActionRow(action: WidgetAction) {
    val context = LocalContext.current
    val packageName = action.requiredPackage ?: return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // No clip of its own: it sits inside a group card now, which is already clipped, and a
            // second rounded shape inside that one showed as a ripple with the wrong corners.
            .clickable { openStorePage(context, packageName) }
            .fillMaxWidth()
            .padding(horizontal = SettingsInset, vertical = 8.dp),
    ) {
        Image(
            painter = painterResource(action.icon),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            action.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { openStorePage(context, packageName) }) { Text("Install") }
    }
}

/**
 * Opens the Play listing, falling back to the web page.
 *
 * `market://` is in the manifest's `<queries>`, so it resolves where Play is present; the https form
 * covers a phone without it and lands in a browser. Both are recorded, because a listing that has
 * been withdrawn will open and show "item not found" — which looks from here exactly like a listing
 * that worked.
 */
private fun openStorePage(context: Context, packageName: String) {
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
    val web = Intent(
        Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri(),
    )
    listOf(market, web).forEach { intent ->
        val started = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (started) {
            EventLog.record("store page $packageName -> ${intent.data?.scheme}")
            return
        }
    }
    EventLog.record("store page $packageName -> nothing handled it")
    Toast.makeText(context, "No app store on this phone", Toast.LENGTH_SHORT).show()
}
