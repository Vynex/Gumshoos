package com.vynex.gumshoos.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Shared building blocks for the settings screens, so a row looks and behaves the same wherever it
 * appears rather than being rebuilt per screen.
 */

/**
 * The inset every settings list holds its content at.
 *
 * Shared because [fullBleedRow] has to undo exactly this much. A row whose bleed and whose list's
 * padding disagree either overhangs the screen or leaves a visible notch.
 */
internal val SettingsInset = 20.dp

/**
 * Lets a row's touch target and ripple reach the screen edges while its content stays inset.
 *
 * The list applies its padding to the scrolling column, so everything inside is already narrower
 * than the window and a ripple can only fill the width its row was given — it stops where the text
 * starts, which reads as a misaligned highlight rather than a row being pressed.
 *
 * Rather than move the inset onto every child in every screen, this measures the row [SettingsInset]
 * wider on each side and draws it shifted back out, while still reporting the original width to the
 * column. Nothing above or below it moves. The row then re-applies the inset internally, so its
 * content lands exactly where it did before.
 *
 * Safe because both lists apply their padding *inside* `verticalScroll`, so the clip bounds are the
 * full window width and the widened row isn't cut off.
 */
internal fun Modifier.fullBleedRow(): Modifier = layout { measurable, constraints ->
    val bleed = SettingsInset.roundToPx()
    val extra = bleed * 2
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(maxWidth = constraints.maxWidth + extra)
    } else {
        constraints
    }
    val placeable = measurable.measure(widened)
    // The reported width excludes the bleed, so the column lays out as though nothing changed.
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) {
        placeable.place(-bleed, 0)
    }
}

/**
 * The one app bar every screen below the root wears.
 *
 * Small, pinned, with a back arrow. **Large bars belong to top-level destinations**, and every screen
 * that takes this one is a subscreen reached from the root list — the M3 reason for a large title is
 * to give a destination presence on arrival, which a settings subscreen does not need and cannot
 * afford: the three shortest screens here do not fill a window, so a large bar on them never
 * collapses and simply eats a third of the screen forever.
 *
 * `LargeTopAppBar` was tried and reverted. Two further reasons it is the wrong component here, both
 * only visible once it was on a phone: it is a *two-row* layout whose top row is a fixed 64dp for
 * the navigation icon and actions, so a screen with neither shows 64dp of nothing above its title;
 * and `exitUntilCollapsed` reads the gesture rather than the content, so on a screen that fits it
 * lets the title be dragged away and leaves a gap. Guarding that with `canScroll` only trades the
 * gap for a bar that never collapses.
 *
 * The M3 spec has since replaced the fixed sizes with *flexible* app bars, which would suit the root
 * exactly — a large title with no wasted leading row. `LargeFlexibleTopAppBar` is `internal` in
 * Material3 1.4.0, the current stable, so it cannot be called yet. When it opens up, the root is the
 * screen to give it to; this bar stays as it is.
 *
 * Pinned rather than static so it takes its scrolled container colour and content passing underneath
 * reads as passing underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

/** A subscreen: [SettingsTopBar] over a scrolling list of controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SettingsTopBar(title, onBack, scrollBehavior) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The root, which has no app bar at all.
 *
 * Its title is a content-led heading that scrolls with the list — the pattern for a top-level
 * destination whose bar would hold nothing but a title. This is deliberately *not* the subscreen
 * bar: the root differing from the screens under it is normal and reads as a hierarchy, where the
 * subscreens differing from **each other** was the actual defect this port set out to fix.
 */
@Composable
internal fun SettingsRootScaffold(
    title: String,
    content: @Composable () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsInset),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * A scaffold with something pinned between the bar and the scrolling content.
 *
 * For screens where the [header] is what the settings below are acting on — a preview that scrolls
 * away is useless the moment you reach the control you wanted to watch it react to.
 *
 * The header sits outside the scroll and takes no horizontal inset of its own, so it can run edge
 * to edge; [content] keeps the same padding every other settings list uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffoldWithHeader(
    title: String,
    onBack: () -> Unit,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SettingsTopBar(title, onBack, scrollBehavior) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            header()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SettingsInset, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * A category on the home list. The summary carries the current state so the common case — checking
 * what something is set to — needs no tap.
 */
@Composable
internal fun CategoryRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    @DrawableRes leadingIcon: Int? = null,
    fullBleed: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // Inside a group card the row must not full-bleed: the card is clipped, so the ripple
            // belongs within its rounded bounds rather than shoved out to the screen edge.
            .then(if (fullBleed) Modifier.fullBleedRow() else Modifier)
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(horizontal = SettingsInset, vertical = 14.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                painter = painterResource(leadingIcon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp).size(24.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shared by every card so they read as one family rather than as separately-invented boxes. */
internal val SettingsCardShape = RoundedCornerShape(16.dp)

/**
 * The container every settings card sits on.
 *
 * `surfaceContainer`, not a translucent `surfaceVariant`. The tonal container roles are the scheme's
 * own answer to "one step off the background", already correct in both light and dark, where a
 * half-alpha colour is a guess that happens to land — and it composites against whatever is behind
 * it, so two cards overlapping or a card over a preview came out a different shade from a card over
 * the background. [SettingsFilterField] was already on `surfaceContainerHigh`, so the app has been
 * disagreeing with itself since that field was written.
 */
@Composable
internal fun settingsCardColour(): Color = MaterialTheme.colorScheme.surfaceContainer

/**
 * A titled group raised onto its own surface.
 *
 * For controls that belong together tightly enough that a heading alone under-sells it — a pair of
 * sliders that only make sense read against each other reads as two unrelated rows when left loose
 * in the list.
 */
@Composable
internal fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(settingsCardColour())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

/**
 * A filled text field styled as an M3 search input: pill shape, leading magnifier, tonal container,
 * no underline indicator, and a clear button once there's something to clear.
 *
 * Shared by every list long enough to need filtering — apps, contacts — so the one that gets
 * restyled doesn't leave the others behind. The M3 `SearchBar` is not an option here: it is fixed to
 * a 360dp width and expands to cover the screen, being a search *destination* rather than an inline
 * list filter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsFilterField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

/**
 * A list-row action as an icon on a squircle card, matching the settings-card family.
 *
 * Groups a row's actions as a set and gives each tap a visible target. Disabled reads as dimmed, not
 * gone, so a placeholder slot is already accounted for.
 *
 * A step *above* [settingsCardColour] rather than equal to it. It used to be the same colour, which
 * worked only because the rows it sits on are on the bare background; the moment a list is grouped
 * into a card the squircle and its card are the same shade and the target disappears.
 */
@Composable
internal fun ActionSquircle(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .alpha(if (enabled) 1f else 0.38f)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * A titleless rounded group for the home list — rows share one container and the clip keeps each
 * ripple within its rounded bounds. Rows inside pass `fullBleed = false` for the same reason.
 */
@Composable
internal fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(settingsCardColour()),
        content = content,
    )
}

/** Hairline between rows in a [SettingsGroupCard]; no vertical padding, so rows stay flush. */
@Composable
internal fun SettingsGroupDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
}

/**
 * The one way to group settings: a caption, a rounded container of rows, and supporting text.
 *
 * This replaces grouping by [SettingsSection] heading and [SettingsDivider] hairline, which is a
 * Material 2 / preference-screen idiom — it draws the *boundaries* between groups and leaves each
 * group itself shapeless. A container says what belongs together without a line having to separate
 * it from what doesn't, and it is what the root list already does.
 *
 * [note] sits under the card rather than over it. Supporting text is read after the thing it
 * supports; copy written as a preamble to controls the reader hasn't reached yet is explaining
 * something they can't see.
 *
 * Rows inside pass `fullBleed = false` — the card is clipped, so a ripple belongs within its rounded
 * bounds rather than shoved out to the screen edge. Put [SettingsGroupDivider] between them where
 * they are separate decisions rather than one set.
 */
@Composable
internal fun SettingsGroup(
    title: String? = null,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // One node, not three siblings. The settings lists arrange their children with
    // `spacedBy(4.dp)`, which would otherwise be inserted *inside* the group — between the caption
    // and its card, and between the card and its note — so a group's internal spacing would depend
    // on the list it was dropped into.
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        SettingsGroupCard(content)
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** A heading inside a screen, for grouping related controls. */
@Composable
internal fun SettingsSection(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}

/**
 * A heading *within* a section.
 *
 * Deliberately quieter than [SettingsSection] on both counts a heading is read by — it drops the
 * accent colour and the title weight — because a sub-section set in the same style as its parent
 * just looks like the parent ended.
 */
@Composable
internal fun SettingsSubSection(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(2.dp))
}

/** Explanatory text under a heading or control. */
@Composable
internal fun SettingsNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Something is wrong and the screen has to say so.
 *
 * Its own container, not red body text. A [SettingsNote] recoloured to `error` is the same shape as
 * every other note on the screen and is read as one of them — which is exactly what happened to the
 * line saying search cannot open at all. A warning has to survive being skimmed.
 */
@Composable
internal fun SettingsWarning(text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(end = 12.dp).size(20.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * A row that does something rather than going somewhere.
 *
 * What every `OutlinedButton(Modifier.fillMaxWidth())` in this app should have been. A stack of
 * full-width buttons is a form, and reads as one — six of them in a row down the Advanced screen
 * gave equal, maximum weight to six things of very unequal importance, none of which is the thing
 * the screen is for. A row states what it does and stays out of the way; a real button is then free
 * to mean "this is the action here".
 *
 * No trailing chevron: nothing opens, so an affordance promising a destination would be a lie.
 *
 * Its title is `bodyMedium`, matching the switch and radio rows rather than [CategoryRow]'s
 * `bodyLarge`. An action is subordinate to a destination, and more to the point these share a card
 * with controls — set in the navigation size, a lone utility action was the loudest thing on the
 * screen.
 */
@Composable
internal fun SettingsActionRow(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    /** As [icon], for one of the stock Material marks rather than a drawable of ours. */
    imageVector: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = SettingsInset, vertical = 14.dp),
    ) {
        val painter = when {
            icon != null -> painterResource(icon)
            imageVector != null -> rememberVectorPainter(imageVector)
            else -> null
        }
        if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp).size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!summary.isNullOrEmpty()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A slider over whole units, with its value read off the row rather than out of a sentence.
 *
 * Five near-identical copies of this existed — icon size, label size, corner radius, opacity, widget
 * margins — each pairing a bare [Slider] with a note reading "Icon size: 42dp". That puts the label
 * and the number in prose above a control that has neither, so the eye has to travel to a sentence
 * to read a value it is currently dragging.
 *
 * [valueText] formats the number because a value is not always a number: a zero corner radius reads
 * "square", not "0dp". The readout is given a floor width so the title beside it doesn't shift as
 * the digit count changes mid-drag.
 *
 * Local state so the readout tracks the drag, while config is written through [onValue] on every
 * change — the previews these sliders drive read config, not the slider. [onValueFinished] is for
 * the writes too expensive to make continuously, like re-pushing a widget across to the launcher.
 *
 * The thumb still has to follow [value] when something *else* moves it — a "Reset to defaults"
 * button. `remember(value)` would do that, but it also re-seeds mid-drag from the write [onValue]
 * just made, which is only harmless while `Slider` is snapping to ticks and handing back exactly the
 * integer we reported. It snaps only when `steps > 0`, and `steps` is derived here, so a range
 * shorter than two [step]s would silently make this a continuous slider that fights its own drag.
 * Comparing against the last value *we* reported takes the guess out: an incoming value we did not
 * produce is an external change and moves the thumb; our own echo never does.
 */
@Composable
internal fun SettingsSliderRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    summary: String? = null,
    valueText: (Int) -> String = { "$it" },
    onValueFinished: (() -> Unit)? = null,
) {
    var current by remember { mutableFloatStateOf(value.toFloat()) }
    var reported by remember { mutableIntStateOf(value) }
    if (value != reported) {
        current = value.toFloat()
        reported = value
    }

    Column(modifier.fillMaxWidth().padding(horizontal = SettingsInset, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (!summary.isNullOrEmpty()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                valueText(current.roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 56.dp),
            )
        }
        Slider(
            value = current,
            onValueChange = {
                current = it
                reported = it.roundToInt()
                onValue(reported)
            },
            onValueChangeFinished = onValueFinished ?: {},
            valueRange = min.toFloat()..max.toFloat(),
            // One notch per step. Guarded because a range shorter than a step would ask for a
            // negative count, which Slider rejects at runtime rather than at compile time.
            steps = (((max - min) / step) - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * [fullBleed] false for a row inside a [SettingsGroup] — see [CategoryRow] for why the card's rows
 * keep their ripple inside its rounded bounds.
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    fullBleed: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (fullBleed) Modifier.fullBleedRow() else Modifier)
            .clickable { onCheckedChange(!checked) }
            .fillMaxWidth()
            .padding(horizontal = SettingsInset, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Which control a [ProviderRow] toggles with. Purely presentational — both mean on or off. */
internal enum class RowControl { SWITCH, CHECKBOX }

/**
 * A provider: brand mark, a toggle, and the two reorder arrows.
 *
 * Arrows rather than drag-and-drop. Reorderable dragging is a dependency or a few hundred lines of
 * gesture handling, for a list of six that is arranged once.
 */
@Composable
internal fun ProviderRow(
    label: String,
    @DrawableRes icon: Int,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    /**
     * Carries the row's horizontal inset, rather than the row baking one in. Inside a
     * [SettingsGroup] that is [SettingsInset]; on a screen not yet ported it is nothing, because the
     * list has already inset the whole column.
     */
    modifier: Modifier = Modifier,
    control: RowControl = RowControl.SWITCH,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        // Image, not Icon: these are full-colour brand marks and Icon would tint them flat.
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            alpha = if (enabled) 1f else 0.38f,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            if (!description.isNullOrEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move $label up")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move $label down")
        }
        when (control) {
            RowControl.SWITCH ->
                Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            RowControl.CHECKBOX ->
                Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

/**
 * One choice in an exclusive set.
 *
 * The only radio row. Three others grew alongside it — one per screen that needed a leading picture
 * the shared one didn't offer — and each drifted in padding, text style and whether the label was
 * even clickable. A leading mark comes either as a drawable ([icon]) or as a live one loaded from
 * the system ([iconBitmap], an app's own icon), which was the whole reason for the copies.
 */
@Composable
internal fun SettingsRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    /** Optional mark shown before the label, for choices that are themselves a picture. */
    @DrawableRes icon: Int? = null,
    /** As [icon], for a mark that isn't a resource — an installed app's own icon. */
    iconBitmap: ImageBitmap? = null,
    /**
     * Whether [icon] should take the text colour. False for brand marks, which a tint flattens —
     * but true for a monochrome glyph, which is authored black and would otherwise vanish into a
     * dark settings background.
     */
    tintIcon: Boolean = false,
    fullBleed: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (fullBleed) Modifier.fullBleedRow() else Modifier)
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(horizontal = SettingsInset, vertical = 8.dp),
    ) {
        // `onClick = null` matters for more than the redundant tap target. RadioButton applies
        // `minimumInteractiveComponentSize()` *only* when it has its own click handler, so passing
        // one makes it lay out 48dp wide and shove the label a further 24dp right — which is where
        // the deep indent came from, and why a radio row and an icon row in the same card started
        // their text in different places. Without it the control is exactly 24dp, the same slot an
        // icon takes, so every row in the family lines up. The row itself is clickable, and a whole
        // row is a far bigger target than 48dp anyway.
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        if (icon != null) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = if (tintIcon) {
                    ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                } else {
                    null
                },
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
        } else if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
}
