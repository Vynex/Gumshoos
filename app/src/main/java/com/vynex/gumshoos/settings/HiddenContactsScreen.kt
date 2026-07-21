package com.vynex.gumshoos.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vynex.gumshoos.ContactEntry
import com.vynex.gumshoos.ContactPhotos
import com.vynex.gumshoos.ContactsRepository
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.R

/**
 * Hide anyone in the address book from results, over one filterable list.
 *
 * Built to read as Manage apps does — the same search-styled filter and the same eye squircle per
 * row — because they are the same decision made about a different list, and a checkbox list beside a
 * squircle list reads as two apps. The shared pieces are [SettingsFilterField] and [ActionSquircle],
 * so a restyle of one carries to the other rather than leaving this screen behind again.
 *
 * Rows are keyed by [ContactEntry.lookupKey], which survives an account re-sync where the row id
 * does not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HiddenContactsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }

    // Read live so a toggle redraws the row it was made on.
    val hidden = GumshoosConfig.hiddenContacts
    val all = ContactsRepository.contacts

    val filtered = remember(query, all) {
        if (query.isBlank()) {
            all
        } else {
            val needle = query.trim().lowercase()
            all.filter {
                it.name.lowercase().contains(needle) ||
                    it.phone?.lowercase()?.contains(needle) == true ||
                    it.email?.lowercase()?.contains(needle) == true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hidden contacts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // A tap anywhere off the field drops focus and lowers the keyboard. Taps a child
                // consumes — a row's button, the field itself — never reach here, so only empty space
                // and the list's own gaps clear it.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                },
        ) {
            Text(
                "Hidden contacts never appear in results.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SettingsInset),
            )

            SettingsFilterField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search contacts",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsInset, vertical = 8.dp),
            )

            if (all.isEmpty()) {
                Text(
                    "No contacts loaded. Grant contacts access and open search once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(SettingsInset),
                )
                return@Column
            }

            // A clear gap sets the filter apart from the list it drives, rather than the two reading
            // as one block.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp),
            ) {
                items(filtered, key = { it.lookupKey }) { entry ->
                    val isHidden = entry.lookupKey in hidden
                    HiddenContactRow(
                        entry = entry,
                        hidden = isHidden,
                        onToggleHidden = {
                            GumshoosConfig.setContactHidden(context, entry.lookupKey, !isHidden)
                        },
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
private fun HiddenContactRow(
    entry: ContactEntry,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsInset, vertical = 6.dp),
    ) {
        ContactAvatar(entry)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = entry.phone ?: entry.email
            if (!detail.isNullOrEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

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

/**
 * The photo, or the initial on its stable colour.
 *
 * Sized to match Manage apps' icon. Photos are decoded on demand — see [ContactPhotos] — so the load
 * hangs off the row rather than off the screen: only what scrolls into view is ever decoded.
 */
@Composable
private fun ContactAvatar(entry: ContactEntry) {
    val context = LocalContext.current
    LaunchedEffect(entry.id) { ContactPhotos.load(context, entry) }
    val photo = ContactPhotos.cached(entry.id)
    if (photo != null) {
        Image(
            bitmap = photo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(ContactPhotos.fallbackColour(entry.name))),
        ) {
            Text(
                text = ContactPhotos.initial(entry.name),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
    }
}
