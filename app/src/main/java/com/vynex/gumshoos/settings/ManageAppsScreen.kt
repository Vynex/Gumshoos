package com.vynex.gumshoos.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.Indication
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vynex.gumshoos.AppEntry
import com.vynex.gumshoos.AppRepository
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.R
import kotlinx.coroutines.delay

/**
 * Rename or hide any installed app, over one filterable list.
 *
 * The merge of the old Custom names and Hidden apps screens: both acted on the same list of apps
 * and the same per-app decisions, so splitting them meant scrolling the same list twice. Each row
 * is a launcher activity — see [AppEntry] — so the list is keyed by [AppEntry.componentKey], which
 * is also what makes the multi-activity apps individually addressable. Package and activity names
 * are deliberately absent: the display name is what someone recognises, and the raw ids are noise.
 *
 * The filter is a search-styled filled text field rather than the M3 SearchBar component — that one
 * is fixed to a 360dp width and expands to cover the screen, being a search *destination* rather
 * than an inline list filter.
 *
 * [highlightKey] is set only when the search window's Edit action deep-links here: the row for that
 * component key is scrolled into view and pulsed with a ripple, so the app you came to edit is the
 * one your eye lands on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManageAppsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AppEntry?>(null) }

    val listState = rememberLazyListState()
    // Hoisted so the pulse can be driven from an effect rather than a touch. Only the highlighted row
    // is wired to it, so a press emitted here ripples that row and no other.
    val highlightInteraction = remember { MutableInteractionSource() }
    val highlightRipple = ripple()
    var highlightSize by remember { mutableStateOf(IntSize.Zero) }

    // Read live so a rename or a hide redraws immediately: both stores are observable state, and the
    // effective alias falls back to a package-wide value saved by a build that keyed things that way.
    val aliases = GumshoosConfig.appAliases
    val hidden = GumshoosConfig.hiddenApps
    fun aliasOf(entry: AppEntry): String? =
        aliases[entry.componentKey] ?: aliases[entry.packageName]
    fun isHidden(entry: AppEntry): Boolean =
        entry.componentKey in hidden || entry.packageName in hidden

    val all = AppRepository.apps.sortedBy { it.label.lowercase() }
    val filtered = remember(query, all, aliases) {
        if (query.isBlank()) {
            all
        } else {
            val needle = query.trim().lowercase()
            all.filter {
                it.label.lowercase().contains(needle) ||
                    aliasOf(it)?.lowercase()?.contains(needle) == true
            }
        }
    }

    // The deep-linked highlight: scroll the target into view, then pulse a ripple centred on it.
    // Scrolling first is what makes the row composed and on-screen so its ripple is actually seen;
    // the frame delay lets it report a size to centre the ripple on before the press is emitted.
    LaunchedEffect(highlightKey) {
        val index = highlightKey?.let { key -> filtered.indexOfFirst { it.componentKey == key } }
            ?.takeIf { it >= 0 } ?: return@LaunchedEffect
        // animateScrollToItem alone crawls through — and composes — every row in between, which is
        // the lag over a long list. Jump instantly to just above the target, then animate the last
        // few rows for a smooth landing that never measures more than a screenful.
        val approach = (index - 4).coerceAtLeast(0)
        if (approach > 0) listState.scrollToItem(approach)
        listState.animateScrollToItem(index)
        delay(80)
        val press = PressInteraction.Press(
            Offset(highlightSize.width / 2f, highlightSize.height / 2f),
        )
        highlightInteraction.emit(press)
        delay(550)
        highlightInteraction.emit(PressInteraction.Release(press))
    }

    editing?.let { entry ->
        AliasDialog(
            label = entry.label,
            initial = aliasOf(entry).orEmpty(),
            onDismiss = { editing = null },
            onConfirm = { text ->
                AppRepository.setAliases(context, entry, text)
                editing = null
            },
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SettingsTopBar("Manage apps", onBack, scrollBehavior) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // A tap anywhere off the field drops focus and lowers the keyboard. Taps a child
                // consumes — a list button, the field itself — never reach here, so only empty space
                // and the list's own gaps clear it.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                },
        ) {
            Text(
                "Rename an app to make it answer to extra words, or hide it from results entirely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SettingsInset),
            )

            SettingsFilterField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search apps",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsInset, vertical = 8.dp),
            )

            if (all.isEmpty()) {
                Text(
                    "No apps indexed yet. Open search once and come back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(SettingsInset),
                )
                return@Column
            }

            // A clear gap sets the filter apart from the list it drives, rather than the two reading
            // as one block.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp),
            ) {
                items(filtered, key = { it.componentKey }) { entry ->
                    val isTarget = entry.componentKey == highlightKey
                    ManageAppRow(
                        entry = entry,
                        alias = aliasOf(entry),
                        hidden = isHidden(entry),
                        onAlias = { editing = entry },
                        onToggleHidden = { AppRepository.setHidden(context, entry, !isHidden(entry)) },
                        highlightInteraction = if (isTarget) highlightInteraction else null,
                        highlightRipple = if (isTarget) highlightRipple else null,
                        onHighlightSized = if (isTarget) ({ highlightSize = it }) else null,
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "Nothing matches \"$query\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(SettingsInset),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageAppRow(
    entry: AppEntry,
    alias: String?,
    hidden: Boolean,
    onAlias: () -> Unit,
    onToggleHidden: () -> Unit,
    highlightInteraction: MutableInteractionSource? = null,
    highlightRipple: Indication? = null,
    onHighlightSized: ((IntSize) -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // The ripple rides the full-width row, before the inset padding, so the deep-link pulse
            // spans the whole item rather than starting where the text does.
            .then(
                if (highlightInteraction != null && highlightRipple != null) {
                    Modifier
                        .onSizeChanged { onHighlightSized?.invoke(it) }
                        .indication(highlightInteraction, highlightRipple)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = SettingsInset, vertical = 6.dp),
    ) {
        Image(
            bitmap = entry.icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!alias.isNullOrEmpty()) {
                Text(
                    "Also matches: $alias",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Placeholder for a future per-app editor. Disabled reads as dimmed rather than absent,
            // so the slot it will occupy is already visible.
            ActionSquircle(
                icon = R.drawable.ic_provider_edit,
                contentDescription = "Edit (coming soon)",
                enabled = false,
                onClick = {},
            )
            ActionSquircle(
                icon = R.drawable.ic_provider_at_sign,
                contentDescription = "Custom names",
                onClick = onAlias,
            )
            ActionSquircle(
                icon = if (hidden) R.drawable.ic_provider_eye_off else R.drawable.ic_provider_eye_on,
                contentDescription = if (hidden) "Hidden — tap to show" else "Shown — tap to hide",
                tint = if (hidden) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                onClick = onToggleHidden,
            )
        }
    }
}
