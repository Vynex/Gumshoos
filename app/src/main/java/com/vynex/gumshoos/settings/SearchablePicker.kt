package com.vynex.gumshoos.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

internal data class PickerItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
)

/**
 * A filterable single-choice list.
 *
 * Shared because the lists it serves are all too long to scroll: 160-odd currencies, 600-odd time
 * zones, and later every installed app. The first entry is always the "follow the device" option,
 * so the default stays reachable without hunting for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchablePicker(
    title: String,
    items: List<PickerItem>,
    selectedId: String?,
    defaultLabel: String,
    defaultDescription: String,
    onPick: (String?) -> Unit,
    onBack: () -> Unit,
) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    PickerRow(
                        title = defaultLabel,
                        subtitle = defaultDescription,
                        selected = selectedId == null,
                        onClick = { onPick(null) },
                    )
                    SettingsDivider()
                }
                items(filtered, key = { it.id }) { entry ->
                    PickerRow(
                        title = entry.title,
                        subtitle = entry.subtitle,
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
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
