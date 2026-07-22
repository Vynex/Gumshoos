package com.vynex.gumshoos.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.vynex.gumshoos.answers.RateRefreshOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vynex.gumshoos.AppRepository
import com.vynex.gumshoos.AssistIntegration
import com.vynex.gumshoos.BrowserApps
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
import com.vynex.gumshoos.SearchWidgetProvider
import com.vynex.gumshoos.SearchWidgetSmallProvider
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

    // A row, and the outcome is its summary. The timing this reports is the number worth having
    // before blaming the index for anything, so it replaces the description rather than joining it.
    SettingsActionRow(
        title = if (running) "Rebuilding…" else "Rebuild index",
        summary = result ?: "Re-reads every installed app, and contacts if permitted.",
        icon = R.drawable.ic_provider_rotate_ccw,
        enabled = !running,
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
    )
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

    SettingsActionRow(
        title = "Refresh widgets",
        summary = if (done) {
            "Pushed. If a widget was opening the wrong thing, try it now."
        } else {
            "Re-sends both widgets to the launcher. Worth doing if one stops responding, or " +
                "opens this app instead of search."
        },
        icon = R.drawable.ic_provider_rotate_ccw,
        onClick = { refreshAllWidgets(context); done = true },
    )
}

/**
 * The event trace, opened in place.
 *
 * Its own group rather than a loose stack of buttons: the trace, the actions on it, and the reason
 * it exists are one thing. Copy and Clear appear only once there is something on screen to act on.
 */
@Composable
internal fun EventTrace() {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var trace by remember { mutableStateOf(EventLog.read()) }
    var expanded by remember { mutableStateOf(false) }

    SettingsGroup(
        title = "Event trace",
        note = "Records what the overlay did and when. This is how the last few misbehaviours " +
            "were pinned down without a debugger attached.",
    ) {
        SettingsActionRow(
            title = if (expanded) "Hide trace" else "Show trace",
            summary = if (expanded) null else "Reads the log from disk.",
            icon = R.drawable.ic_provider_rotate_ccw,
            onClick = {
                trace = EventLog.read()
                expanded = !expanded
            },
        )

        if (expanded) {
            SettingsGroupDivider()
            Text(
                text = trace.ifBlank { "No events recorded yet." },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SettingsInset, vertical = 12.dp),
            )
            SettingsGroupDivider()
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = SettingsInset, vertical = 8.dp),
            ) {
                TextButton(
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(trace)) },
                ) { Text("Copy") }
                TextButton(onClick = { EventLog.clear(); trace = "" }) { Text("Clear") }
            }
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
        )
        SettingsRadioRow(
            title = "System-wide usage",
            description = "Counts foreground time across the whole phone.",
            selected = source == UsageSource.SYSTEM,
            onClick = {
                source = UsageSource.SYSTEM
                GumshoosConfig.setUsageSource(context, UsageSource.SYSTEM)
            },
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
            description = "Lets the phone decide, may show a picker.",
            selected = selected == null,
            onClick = { choose(null) },
        )
        handlers.forEach { handler ->
            SettingsRadioRow(
                title = handler.label,
                description = "",
                selected = selected == handler.packageName,
                onClick = { choose(handler.packageName) },
                iconBitmap = handler.icon,
            )
        }
    }
}

/**
 * Which browser a provider set to "open in a browser" hands its query to.
 *
 * Deliberately the same shape as [EmailAppPicker], because it answers the same kind of question and
 * the two would read as different features if one were a dialog and the other a list.
 *
 * No re-warm, unlike the email picker: that one changes an icon the overlay decoded at warm time,
 * whereas this is read from config at the moment a search is launched.
 */
@Composable
internal fun BrowserPicker() {
    val context = LocalContext.current
    var browsers by remember { mutableStateOf<List<BrowserApps.Browser>>(emptyList()) }
    val selected = GumshoosConfig.preferredBrowser

    LaunchedEffect(Unit) { browsers = BrowserApps.installed(context) }

    SettingsGroup(
        title = "Browser",
        note = if (browsers.isEmpty()) {
            "No browsers found on this phone."
        } else {
            "Used by any provider above that's set to open in a browser."
        },
    ) {
        // No mark, and no space held for one — Automatic is not an app, so a glyph invented to fill
        // the slot the app icons occupy would be claiming it is one. Same call as the email picker.
        SettingsRadioRow(
            title = "Automatic",
            description = "Lets the phone decide, may show a picker.",
            selected = selected == null,
            onClick = { GumshoosConfig.setPreferredBrowser(context, null) },
        )
        browsers.forEach { browser ->
            SettingsRadioRow(
                title = browser.label,
                description = "",
                selected = selected == browser.packageName,
                onClick = { GumshoosConfig.setPreferredBrowser(context, browser.packageName) },
                iconBitmap = browser.icon,
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
    var lastOutcome by remember { mutableStateOf<RateRefreshOutcome?>(null) }
    var coolingDown by remember { mutableStateOf(CurrencyRates.cooldownRemainingMillis() > 0) }

    // One delayed wake-up rather than a ticking timer: nothing on screen counts down, so the row
    // only needs to hear about the single moment it becomes tappable again. Without it the row
    // would stay dimmed until some unrelated recomposition happened to run. Keyed on the attempt,
    // so a new one restarts the wait, and cancelled for free when the screen goes away.
    LaunchedEffect(CurrencyRates.lastAttemptAtMillis) {
        val remaining = CurrencyRates.cooldownRemainingMillis()
        coolingDown = remaining > 0
        if (remaining > 0) {
            delay(remaining)
            coolingDown = false
        }
    }

    // The status is the row's own summary rather than a separate paragraph: what the rates are and
    // the button that changes them are one thing, and reading them apart was never useful.
    SettingsGroup(title = "Exchange rates") {
        SettingsActionRow(
            title = when {
                CurrencyRates.refreshing -> "Refreshing…"
                coolingDown -> "Just refreshed"
                else -> "Refresh rates now"
            },
            summary = when {
                coolingDown && !CurrencyRates.refreshing -> "Rates only change once a day."
                table == null -> "Not fetched rates yet."
                else -> "${table.rates.size} currencies, based on ${table.base}. Data from " +
                    AnswerFormat.relativeTime(table.fetchedAtMillis, System.currentTimeMillis()) +
                    "."
            },
            icon = R.drawable.ic_provider_rotate_ccw,
            enabled = !CurrencyRates.refreshing && !coolingDown,
            onClick = {
                lastOutcome = null
                scope.launch {
                    val base = AnswerContextFactory.forDevice(context).currencyCode
                    lastOutcome = CurrencyRates.refresh(context, base, force = true)
                }
            },
        )
    }
    // Only a genuine failure warns. A refused tap is us declining to ask, and saying the service
    // couldn't be reached would be a lie about someone else.
    if (lastOutcome == RateRefreshOutcome.FAILED) {
        SettingsWarning("Couldn't reach the rate service. Any cached rates are still in use.")
    }
}

/**
 * The second launcher icon.
 *
 * A switch, not a checkbox. It is an on/off setting like every other on/off setting here, and the
 * checkbox was only ever there because this control predates the shared row.
 */
@Composable
internal fun DrawerIconSettings() {
    val context = LocalContext.current
    var drawerIcon by remember { mutableStateOf(SearchShortcuts.isDrawerIconEnabled(context)) }

    // Why it's slower — the icon goes through LaunchSearchActivity, a trampoline, because a
    // shortcut can't start a service — is the mechanism, and stays here rather than on screen.
    SettingsGroup(
        title = "App drawer",
        note = "A little slower to open than the widget.",
    ) {
        SettingsSwitchRow(
            title = "Second search icon",
            description = "Adds a \"Gumshoos Search\" icon to the app drawer.",
            checked = drawerIcon,
            onCheckedChange = {
                drawerIcon = it
                SearchShortcuts.setDrawerIconEnabled(context, it)
            },
        )
    }
}

/**
 * The three things that can be dropped on the home screen.
 *
 * Each hands a request to the launcher, which shows its own confirmation, chooses where the thing
 * lands, and is free to decline outright — some launchers accept neither kind, and one that accepts
 * shortcuts can still refuse widgets, so the two capabilities are asked about separately.
 *
 * The outcome is surfaced rather than assumed. A refusal and a success look identical from this
 * screen, and the innocent explanation — that it landed on a home screen page nobody is currently
 * looking at — is the one a silent failure hides behind.
 *
 * The icon and the drawer switch above are deliberately independent: the pinned shortcut aims at
 * [LaunchSearchActivity][com.vynex.gumshoos.LaunchSearchActivity] directly, so it works whether or
 * not the drawer alias is enabled, and disabling this alongside it would grey out a control that
 * would have worked.
 */
@Composable
internal fun AddToHomeScreenSettings() {
    val context = LocalContext.current
    val canPinShortcut = SearchShortcuts.canPin(context)
    val canPinWidget = SearchShortcuts.canPinWidget(context)
    var refused by remember { mutableStateOf(false) }

    SettingsGroup(
        title = "Add to home screen",
        // Nothing to say in the ordinary case — the launcher's own confirmation says it better a
        // moment later. The note exists for the launcher that won't play along at all.
        note = if (canPinShortcut || canPinWidget) {
            null
        } else {
            "This launcher won't let apps add things. Use its own widget list instead."
        },
    ) {
        SettingsActionRow(
            title = "Search icon",
            summary = "One icon that opens search.",
            enabled = canPinShortcut,
            onClick = { refused = !SearchShortcuts.requestPin(context) },
        )
        SettingsGroupDivider()
        SettingsActionRow(
            title = "Search bar widget",
            summary = "The wide bar. Fastest way in.",
            enabled = canPinWidget,
            onClick = {
                refused = !SearchShortcuts.requestPinWidget(
                    context,
                    SearchWidgetProvider::class.java,
                )
            },
        )
        SettingsGroupDivider()
        SettingsActionRow(
            title = "App icon widget",
            summary = "Just the icon, to sit among your apps.",
            enabled = canPinWidget,
            onClick = {
                refused = !SearchShortcuts.requestPinWidget(
                    context,
                    SearchWidgetSmallProvider::class.java,
                )
            },
        )
    }

    if (refused) {
        SettingsWarning("Your launcher turned that down. Add it from its widget list instead.")
    }
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

    val assistState = when {
        isAssistant == true && !offered ->
            "Still your assistant, but no longer offered. Pick another one in system settings."
        isAssistant == true -> "Selected. The assistant gesture opens search."
        // Some phones only offer their own assistant, so this can be unfinishable. Not said on
        // screen: it reads as an excuse, and the next step is the same either way.
        isAssistant == false && offered -> "Listed, but not chosen yet."
        isAssistant == false -> "Not selected."
        // Null is "couldn't tell", not "no". Saying "not selected" on a device where the check
        // is unavailable would be a confident wrong answer to the one question this row asks.
        else -> "Couldn't check on this phone."
    }
    // The contradictory state is the one worth raising: still the selected assistant, no longer
    // offered as one, so the gesture is about to stop working with nothing on screen saying why.
    val contradictory = isAssistant == true && !offered

    SettingsGroup(
        title = "Assistant",
        note = if (contradictory) null else assistState,
    ) {
        SettingsSwitchRow(
            title = "Offer Gumshoos as an assistant",
            description = "Lets the assistant gesture open search. Choosing it is a second step.",
            checked = offered,
            onCheckedChange = {
                offered = it
                AssistIntegration.setEntryEnabled(context, it)
            },
        )
        SettingsGroupDivider()
        // No summary: the row above already says a second step is coming, and the reason for it —
        // no app may make itself the assistant — is a platform rule, not something to read here.
        SettingsActionRow(
            title = "Open assistant settings",
            onClick = { pickerMissing = !AssistIntegration.openPicker(context) },
        )
    }

    if (contradictory) SettingsWarning(assistState)

    if (pickerMissing) {
        SettingsWarning("Couldn't open it. Look under Settings → Apps → Default apps.")
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
