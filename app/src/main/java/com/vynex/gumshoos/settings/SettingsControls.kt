package com.vynex.gumshoos.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vynex.gumshoos.answers.AnswerContextFactory
import com.vynex.gumshoos.answers.AnswerFormat
import com.vynex.gumshoos.answers.CurrencyRates
import kotlinx.coroutines.launch
import com.vynex.gumshoos.AppRepository
import com.vynex.gumshoos.AssistIntegration
import com.vynex.gumshoos.ContactAction
import com.vynex.gumshoos.ContactsRepository
import com.vynex.gumshoos.CrashLog
import com.vynex.gumshoos.EventLog
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.LaunchHistory
import com.vynex.gumshoos.OverlayService
import com.vynex.gumshoos.R
import com.vynex.gumshoos.refreshAllWidgets
import com.vynex.gumshoos.SearchProviders
import com.vynex.gumshoos.SearchShortcuts
import com.vynex.gumshoos.UsageSource

/**
 * The individual settings controls, moved out of MainActivity unchanged when the screen was split
 * into categories. Grouping them was the work; the controls themselves already behaved.
 */

/**
 * Rebuilds the app and contact indexes by hand.
 *
 * Package changes are picked up automatically now, so this is a backstop rather than the normal
 * route: it covers the window where the service wasn't running to hear a change, and it is the
 * quickest way to tell an indexing problem from a matching one.
 *
 * "Start & pre-warm" is not the same thing and never was — it starts the service, but on one that
 * is already running nothing re-runs `onCreate`, which is where the index is built.
 */
@Composable
internal fun IndexRefresh() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    SettingsSection("Rebuild index")
    SettingsNote(
        result ?: "Re-reads every installed app and, if permitted, contacts. The app index also " +
            "records how long it took, which is the number worth knowing before blaming the index " +
            "for anything.",
    )
    Button(
        onClick = {
            running = true
            result = null
            scope.launch {
                val started = System.currentTimeMillis()
                runCatching { AppRepository.load(context) }
                    .onFailure { EventLog.record("manual reindex FAILED ${it.javaClass.simpleName}") }
                runCatching { SearchProviders.refreshAvailability(context) }
                if (ContactsRepository.hasPermission(context)) {
                    runCatching { ContactsRepository.load(context) }
                }
                val elapsed = System.currentTimeMillis() - started
                result = "${AppRepository.apps.size} apps, " +
                    "${ContactsRepository.contacts.size} contacts, in ${elapsed}ms."
                running = false
            }
        },
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(if (running) "Rebuilding…" else "Rebuild index now")
    }
}

/**
 * Rebuilds the placed widgets by hand.
 *
 * The upgrade case is handled automatically now, but this stays as the recovery path, because the
 * failure it addresses is one where the widget can't open anything — including this screen. Cheap
 * enough to reach for whenever a widget looks stale.
 */
@Composable
internal fun WidgetRefresh() {
    val context = LocalContext.current
    var done by remember { mutableStateOf(false) }

    SettingsSection("Refresh widgets")
    SettingsNote(
        if (done) {
            "Pushed. If a widget was opening the wrong thing, try it now."
        } else {
            "Re-sends both widgets to the launcher. Worth doing if one stops responding, or " +
                "opens this app instead of search — that means the launcher is holding a copy " +
                "from before an upgrade."
        },
    )
    OutlinedButton(
        onClick = { refreshAllWidgets(context); done = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Refresh widgets")
    }
}

@Composable
internal fun EventTrace() {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var trace by remember { mutableStateOf(EventLog.read()) }
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { trace = EventLog.read(); expanded = true }) {
                Text(if (expanded) "Refresh trace" else "Show event trace")
            }
            if (expanded) {
                OutlinedButton(
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(trace)) },
                ) { Text("Copy") }
                OutlinedButton(onClick = { EventLog.clear(); trace = "" }) { Text("Clear") }
            }
        }

        if (expanded) {
            Text(
                text = trace.ifBlank { "No events recorded yet." },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/**
 * Which signal orders the Frequent and Recent tabs, and the access one of the two options needs.
 *
 * The two answer subtly different questions. System usage measures foreground time across the whole
 * phone, so something left open in the background outranks what you actually reach for. Gumshoos'
 * own history counts launches made here — narrower, but a truer picture of how the launcher is
 * used, and it needs no permission at all.
 *
 * The choice and the grant are one group rather than a picker and a loose button below it, because
 * the grant only means anything under one of the two options. It appears when that option is chosen
 * and the access is missing, and is absent otherwise — not dimmed. A greyed row saying "Grant usage
 * access" invites the reading that the grant is unavailable, when what is true is that nothing needs
 * granting. [hasUsage] is passed in because the screen re-reads it on resume: it is granted in a
 * system screen we get no result back from.
 */
@Composable
internal fun UsageSourcePicker(hasUsage: Boolean) {
    val context = LocalContext.current
    var source by remember { mutableStateOf(GumshoosConfig.usageSource) }
    val tracked = LaunchHistory.stats.size
    var confirmReset by remember { mutableStateOf(false) }

    val needsGrant = source == UsageSource.SYSTEM && !hasUsage

    SettingsGroup(
        title = "Frequent & Recent ordering",
        // Nothing to add in the state that already has a warning of its own below.
        note = when {
            source == UsageSource.GUMSHOOS ->
                "Usage access isn't needed for this option and can be revoked."
            hasUsage -> "Granted. Apps are ordered by system-wide usage."
            else -> null
        },
    ) {
        SettingsRadioRow(
            title = "Launches from Gumshoos",
            description = "Recommended. Counts apps opened from search.",
            selected = source == UsageSource.GUMSHOOS,
            onClick = {
                source = UsageSource.GUMSHOOS
                GumshoosConfig.setUsageSource(context, UsageSource.GUMSHOOS)
            },
            fullBleed = false,
        )
        SettingsRadioRow(
            title = "System-wide usage",
            description = "Counts foreground time across the whole phone.",
            selected = source == UsageSource.SYSTEM,
            onClick = {
                source = UsageSource.SYSTEM
                GumshoosConfig.setUsageSource(context, UsageSource.SYSTEM)
            },
            fullBleed = false,
        )

        // The grant belongs with the choice, since it is what makes the chosen one work.
        if (needsGrant) {
            SettingsGroupDivider()
            SettingsActionRow(
                title = "Grant usage access",
                summary = "Opens a system screen rather than a prompt.",
                icon = R.drawable.ic_provider_terminal,
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
            )
        }
    }

    if (needsGrant) {
        SettingsWarning(
            "Ordering by system usage needs usage access, and it isn't granted — Frequent and " +
                "Recent stay alphabetical until it is.",
        )
    }

    // Its own card. It rode along under the two radios at first, which put an action about stored
    // data inside the group that chooses between two options — and pushed that group's note so far
    // from the choice it describes that the note read as a comment on the reset button.
    if (source == UsageSource.GUMSHOOS && tracked > 0) {
        SettingsGroup {
            SettingsActionRow(
                title = "Reset launch history",
                summary = "Frequent and Recent go back to alphabetical.",
                icon = R.drawable.ic_provider_trash,
                onClick = { confirmReset = true },
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset launch history?") },
            text = {
                Text(
                    "Launch counts for $tracked app${if (tracked == 1) "" else "s"} will be " +
                        "cleared. Frequent and Recent go back to alphabetical until you open " +
                        "something. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LaunchHistory.clear(context)
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Which app the contact row's email action uses.
 *
 * Worth choosing explicitly rather than resolving: with several mail apps installed and none set as
 * the system default, Android hands back its chooser activity, whose icon is a generic cogwheel.
 */
@Composable
internal fun EmailAppPicker() {
    val context = LocalContext.current
    var handlers by remember { mutableStateOf<List<ContactsRepository.ActionHandler>>(emptyList()) }
    val selected = GumshoosConfig.contactActionPackages[ContactAction.EMAIL]

    LaunchedEffect(Unit) {
        handlers = ContactsRepository.handlersFor(context, ContactAction.EMAIL)
    }

    fun choose(packageName: String?) {
        GumshoosConfig.setContactActionPackage(context, ContactAction.EMAIL, packageName)
        // Re-warm so the overlay picks up the new icon without waiting for a restart.
        context.startForegroundService(OverlayService.intent(context, OverlayService.ACTION_WARM))
    }

    SettingsGroup(
        title = "Email app",
        note = if (handlers.isEmpty()) {
            "No mail apps found on this phone."
        } else {
            "Used for the mail action on a contact."
        },
    ) {
        // No mark, and no space held for one. Automatic is not an app, so a glyph invented to fill
        // the slot the app icons occupy would be claiming it is one — the label sitting further
        // left is the honest signal that this row is a different kind of option.
        SettingsRadioRow(
            title = "Automatic",
            description = "Lets the phone decide, which may show a chooser.",
            selected = selected == null,
            onClick = { choose(null) },
            fullBleed = false,
        )
        handlers.forEach { handler ->
            SettingsRadioRow(
                title = handler.label,
                description = "",
                selected = selected == handler.packageName,
                onClick = { choose(handler.packageName) },
                iconBitmap = handler.icon,
                fullBleed = false,
            )
        }
    }
}

/**
 * Exchange rate status, and a way to refresh without waiting for the automatic one.
 *
 * Rates refresh at warm time at most once every 20 hours, which is right for a background job and
 * wrong when you want a current number now.
 */
@Composable
internal fun CurrencyRatesSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val table = CurrencyRates.table
    var lastAttemptFailed by remember { mutableStateOf(false) }

    // The status is the row's own summary rather than a separate paragraph: what the rates are and
    // the button that changes them are one thing, and reading them apart was never useful.
    SettingsGroup(title = "Exchange rates") {
        SettingsActionRow(
            title = if (CurrencyRates.refreshing) "Refreshing…" else "Refresh rates now",
            summary = when {
                table == null -> "No rates yet. Conversions stay hidden until some are fetched — " +
                    "an invented exchange rate would be worse than no answer."
                else -> "${table.rates.size} currencies, based on ${table.base}. Updated " +
                    AnswerFormat.relativeTime(table.fetchedAtMillis, System.currentTimeMillis()) +
                    "."
            },
            imageVector = Icons.Filled.Refresh,
            enabled = !CurrencyRates.refreshing,
            onClick = {
                lastAttemptFailed = false
                scope.launch {
                    val base = AnswerContextFactory.forDevice(context).currencyCode
                    lastAttemptFailed = !CurrencyRates.refresh(context, base, force = true)
                }
            },
        )
    }
    if (lastAttemptFailed) {
        SettingsWarning("Couldn't reach the rate service. Any cached rates are still in use.")
    }
}

@Composable
internal fun SearchIconSettings() {
    val context = LocalContext.current
    var drawerIcon by remember { mutableStateOf(SearchShortcuts.isDrawerIconEnabled(context)) }

    SettingsSection("Search icons")
    Text(
        "A shortcut can only launch an activity, so these pass through a hidden one on the way to " +
            "the search window. The widget reaches it directly and should still be quicker — " +
            "compare the millisecond readout under the search bar.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                drawerIcon = !drawerIcon
                SearchShortcuts.setDrawerIconEnabled(context, drawerIcon)
            },
    ) {
        Checkbox(
            checked = drawerIcon,
            onCheckedChange = {
                drawerIcon = it
                SearchShortcuts.setDrawerIconEnabled(context, it)
            },
        )
        Spacer(Modifier.width(4.dp))
        Column {
            Text("Second icon in the app drawer", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Adds a separate \"Gumshoos Search\" entry that opens the search window, alongside " +
                    "the normal icon that opens this screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    OutlinedButton(
        onClick = { SearchShortcuts.requestPin(context) },
        enabled = SearchShortcuts.canPin(context),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Pin a search icon to the home screen") }
}

/**
 * The assistant option.
 *
 * Deliberately two steps rather than one switch, because that is what the platform allows: the
 * switch only puts Gumshoos in the system's list, and the assistant itself can only be chosen in
 * system settings. Presenting it as a single toggle would leave the gesture doing nothing with the
 * setting reading "on", which is the failure this app keeps trying not to ship.
 */
@Composable
internal fun AssistSettings() {
    val context = LocalContext.current
    var offered by remember { mutableStateOf(AssistIntegration.isEntryEnabled(context)) }
    // Chosen in a system screen we get no result back from, so it is re-read on resume.
    val isAssistant = rememberOnResume { AssistIntegration.isCurrentAssistant(context) }
    var pickerMissing by remember { mutableStateOf(false) }

    SettingsSection("Assistant")
    SettingsNote(
        "Opens the same search window the widget does, from whatever gesture your phone uses for " +
            "the assistant — long-pressing the power button, or the corner swipe. It arrives as " +
            "an activity, so it takes the same trampoline the shortcut does and reads a little " +
            "slower than the widget; the readout tags it \"assist\".",
    )

    SettingsSwitchRow(
        title = "Offer Gumshoos as an assistant",
        description = "Adds Gumshoos to the phone's list of assistant apps. It doesn't select it " +
            "— no app is allowed to give itself that role.",
        checked = offered,
        onCheckedChange = {
            offered = it
            AssistIntegration.setEntryEnabled(context, it)
        },
    )

    val assistState = when {
        isAssistant == true && !offered ->
            "Gumshoos is still the selected assistant, but is no longer being offered. Pick " +
                "another one in system settings, or the gesture will stop doing anything."
        isAssistant == true -> "Selected. The assistant gesture opens Gumshoos."
        isAssistant == false && offered ->
            "Listed but not selected — choose Gumshoos in system settings to finish. Some " +
                "phones only offer their own assistant here, in which case this can't be set."
        isAssistant == false -> "Not selected."
        // Null is "couldn't tell", not "no". Saying "not selected" on a device where the check
        // is unavailable would be a confident wrong answer to the one question this row asks.
        else -> "Couldn't read the current assistant on this device. The button below still " +
            "opens the picker."
    }
    // The contradictory state is the one worth raising: still the selected assistant, no longer
    // offered as one, so the gesture is about to stop working with nothing on screen saying why.
    if (isAssistant == true && !offered) {
        SettingsWarning(assistState)
    } else {
        SettingsNote(assistState)
    }

    OutlinedButton(
        onClick = { pickerMissing = !AssistIntegration.openPicker(context) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Open assistant settings") }

    if (pickerMissing) {
        SettingsWarning(
            "This phone didn't answer to any of the assistant settings screens. Look under " +
                "Settings → Apps → Default apps.",
        )
    }
}

@Composable
internal fun CrashReport() {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    val trace = crash ?: return

    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Last crash", style = MaterialTheme.typography.titleMedium)
            Text(
                text = trace,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(trace))
                    },
                ) { Text("Copy") }
                OutlinedButton(
                    onClick = {
                        CrashLog.clear(context)
                        crash = null
                    },
                ) { Text("Clear") }
            }
        }
    }
}
