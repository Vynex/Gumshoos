package com.vynex.gumshoos.settings

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

internal data class PickerItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
)

/**
 * A filterable single-choice list.
 *
 * Shared because the lists it serves are all too long to scroll: 160-odd currencies and 600-odd time
 * zones. The first entry is always the "follow the device" option, so the default stays reachable
 * without hunting for it.
 *
 * Built on the same pieces as Manage apps and Hidden contacts — [SettingsTopBar],
 * [SettingsFilterField], and the shared [SettingsRadioRow]. It had been left on an
 * `OutlinedTextField` and a private row of its own, which is exactly the state Hidden contacts was
 * in before it was brought across: a list screen that reads as a different app from the list screen
 * beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchablePicker(
    title: String,
    items: List<PickerItem>,
    selectedId: String?,
    defaultLabel: String,
    defaultDescription: String,
    filterPlaceholder: String,
    onPick: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, items) {
        if (query.isBlank()) {
            items
        } else {
            val needle = query.trim().lowercase()
            items.filter {
                it.title.lowercase().contains(needle) ||
                    it.id.lowercase().contains(needle) ||
                    it.subtitle?.lowercase()?.contains(needle) == true
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SettingsTopBar(title, onBack, scrollBehavior) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // A tap anywhere off the field drops focus and lowers the keyboard, as the other
                // two list screens do. Taps a child consumes never reach here.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                },
        ) {
            SettingsFilterField(
                query = query,
                onQueryChange = { query = it },
                placeholder = filterPlaceholder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsInset, vertical = 8.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp),
            ) {
                item {
                    // Not full-bleed: the rows are already the full window width here, so widening
                    // one would push it off the edge rather than let its ripple reach further.
                    SettingsRadioRow(
                        title = defaultLabel,
                        description = defaultDescription,
                        selected = selectedId == null,
                        onClick = { onPick(null) },
                    )
                    // A real separator rather than a grouping device: it divides the one default
                    // from the several hundred alternatives, which is a different kind of break.
                    SettingsDivider()
                }
                items(filtered, key = { it.id }) { entry ->
                    SettingsRadioRow(
                        title = entry.title,
                        description = entry.subtitle.orEmpty(),
                        selected = entry.id == selectedId,
                        onClick = { onPick(entry.id) },
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
