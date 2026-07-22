package com.vynex.gumshoos.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.WeatherRepository
import com.vynex.gumshoos.widget.GeocodePlace
import com.vynex.gumshoos.widget.WeatherUnit

/**
 * Everything the weather action needs to work, behind the gear on its row.
 *
 * **Nothing here is written until Save.** Every other control in this app applies live, which is
 * right for a slider whose effect you can see — but this dialog holds a key, a place and a unit that
 * only mean anything together, and a half-entered set of them is not a state the widget should be
 * pushed into. That is what gives Cancel something to undo, and it is why this is the one screen
 * here with a Cancel at all.
 *
 * Holding it locally also fixes a bug the live version had: choosing Auto wrote a cleared place
 * immediately, so switching back left the city gone for good. Auto now only stops it being *used*;
 * the picked place survives in [chosenLabel] and Save decides what is committed.
 *
 * **The location permission is asked for here, not on the Permissions screen.** That screen is what
 * the app needs to work at all; this is one optional action's convenience, and the request belongs
 * beside the reason for it. Anyone who never turns weather on is never asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeatherSettingsDialog(onDismiss: (refetch: Boolean) -> Unit) {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }

    // Captured once. Save compares against these to decide whether anything a *fetch* depends on
    // moved — the unit is deliberately not among them, since readings are stored in Celsius and
    // converted when drawn, so switching C/F can never need a request.
    val savedKey = remember { GumshoosConfig.weatherApiKey }
    val savedPlace = remember { GumshoosConfig.weatherPlace }

    var key by remember { mutableStateOf(savedKey) }
    var unit by remember { mutableStateOf(GumshoosConfig.weatherUnit) }
    var auto by remember { mutableStateOf(savedPlace.isBlank()) }
    var chosenLabel by remember { mutableStateOf(savedPlace) }
    var chosenLat by remember { mutableStateOf(GumshoosConfig.weatherLat) }
    var chosenLon by remember { mutableStateOf(GumshoosConfig.weatherLon) }

    var query by remember { mutableStateOf(savedPlace) }
    var results by remember { mutableStateOf<List<GeocodePlace>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    // "Choose a city" with nothing picked is the one state that must not be saved: the action would
    // be on with no coordinates and no route to any, so the bar would sit on a dash forever with
    // nothing on screen explaining it.
    val valid = auto || (chosenLabel.isNotBlank() && !chosenLat.isNaN() && !chosenLon.isNaN())

    fun search() {
        if (query.isBlank() || key.isBlank() || searching) return
        searching = true
        val q = query
        val k = key
        Thread {
            val found = WeatherRepository.searchPlaces(q, k)
            handler.post {
                results = found
                searching = false
                searched = true
                menuOpen = found.isNotEmpty()
            }
        }.start()
    }

    fun save() {
        GumshoosConfig.setWeatherApiKey(context, key)
        GumshoosConfig.setWeatherUnit(context, unit)
        if (auto) {
            GumshoosConfig.clearWeatherPlace(context)
        } else {
            GumshoosConfig.setWeatherPlace(context, chosenLabel, chosenLat, chosenLon)
        }
        onDismiss(
            GumshoosConfig.weatherApiKey != savedKey || GumshoosConfig.weatherPlace != savedPlace,
        )
    }

    AlertDialog(
        // Wider than the platform default. This one carries a key, a mode, a search with its
        // results and a unit; at the stock width every result row wraps onto three lines.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        // Tapping outside cancels, so it discards rather than committing a half-set state — which
        // is also what stops an invalid selection ever being saved by accident.
        onDismissRequest = { onDismiss(false) },
        title = { Text("Weather") },
        text = {
            Column {
                Text(
                    "Data is fetched from OpenWeather, you need to provide your own API key.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                SettingsTextField(
                    value = key,
                    onValueChange = { key = it },
                    placeholder = "API key",
                    // Masked: a secret on a screen that gets screenshotted. No reveal toggle — a
                    // key is pasted, not read back, so its only use would be undoing this.
                    masked = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(20.dp))
                Text("Location", style = MaterialTheme.typography.bodyMedium)
                SettingsRadioRow(
                    title = "Auto",
                    description = "Follows your phone",
                    selected = auto,
                    onClick = { auto = true },
                )
                SettingsRadioRow(
                    title = "Choose a city",
                    description = chosenLabel.ifBlank { "Search for one below" },
                    selected = !auto,
                    onClick = { auto = false },
                )

                if (auto) {
                    if (!granted) {
                        Spacer(Modifier.height(8.dp))
                        SettingsWarning("Auto needs location access.")
                        TextButton(
                            onClick = { request.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                        ) {
                            Text("Grant location access")
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = menuOpen && results.isNotEmpty(),
                        onExpandedChange = { menuOpen = it && results.isNotEmpty() },
                    ) {
                        SettingsTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                // A result list under a changed query is a lie — the rows would
                                // still answer whatever was typed before.
                                results = emptyList()
                                searched = false
                                menuOpen = false
                            },
                            placeholder = "Search for a town or city",
                            // The magnifier labels the field, it is not a button. Search runs on
                            // the keyboard's return key: one request per keystroke is the wrong
                            // default against a metered API, and a separate button was a control
                            // too many for a field that already implies its own action.
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onImeAction = ::search,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = menuOpen && results.isNotEmpty(),
                            onDismissRequest = { menuOpen = false },
                        ) {
                            results.forEach { found ->
                                DropdownMenuItem(
                                    text = { Text(found.label) },
                                    onClick = {
                                        chosenLabel = found.label
                                        chosenLat = found.lat
                                        chosenLon = found.lon
                                        query = found.label
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    val hint = when {
                        searching -> "Searching…"
                        searched && results.isEmpty() -> "No matches."
                        else -> null
                    }
                    hint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("Show temperature in", style = MaterialTheme.typography.bodyMedium)
                // One row: two options of one word each, which stacked spent a third of the
                // dialog's height on a choice that reads at a glance.
                Row(Modifier.fillMaxWidth()) {
                    WeatherUnit.entries.forEach { option ->
                        SettingsRadioRow(
                            title = option.label,
                            description = "",
                            selected = unit == option,
                            // Never a refetch: readings are stored in Celsius and converted when
                            // drawn, so this works offline and costs no request.
                            onClick = { unit = option },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = valid) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(false) }) { Text("Cancel") }
        },
    )
}
