package com.vynex.gumshoos.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.vynex.gumshoos.AnimationMode
import com.vynex.gumshoos.AppMatcher
import com.vynex.gumshoos.AppPermission
import com.vynex.gumshoos.BrowserApps
import com.vynex.gumshoos.BuildConfig
import com.vynex.gumshoos.ContactsRepository
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.LaunchMethod
import com.vynex.gumshoos.OverlayService
import com.vynex.gumshoos.R
import com.vynex.gumshoos.SearchProvider
import com.vynex.gumshoos.SearchProviders
import com.vynex.gumshoos.SuggestionSource
import com.vynex.gumshoos.UsageRepository
import com.vynex.gumshoos.WebSuggestions
import com.vynex.gumshoos.answers.AnswerProviders
import com.vynex.gumshoos.answers.CurrencyRates
import com.vynex.gumshoos.answers.MeasurementSystem
import sh.calvin.reorderable.ReorderableColumn
import java.time.ZoneId
import java.util.Currency

/**
 * Re-reads something whenever the screen comes back to the front, because it is changed elsewhere
 * — in a system settings screen or a store listing we sent the user to, and can't get a result back
 * from.
 */
@Composable
internal fun <T> rememberOnResume(read: () -> T): T {
    var value by remember { mutableStateOf(read()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) value = read()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return value
}

/** Re-reads a permission whenever the screen comes back to the front, since it's granted elsewhere. */
@Composable
private fun rememberPermission(check: () -> Boolean): Boolean = rememberOnResume(check)

/**
 * Everything about what a query turns up: how it matches, what answers itself, contacts, and the
 * hand-off providers. Each is its own screen; this is the hub that gathers them.
 */
@Composable
internal fun SearchScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    val hasContacts = rememberPermission { ContactsRepository.hasPermission(context) }

    SettingsScaffold(title = "Search", onBack = onBack) {
        // Dividered, unlike Matching's switches: these are four separate destinations rather than
        // one set of choices, which is the same call the root list makes.
        SettingsGroup {
            CategoryRow(
                title = "Matching",
                summary = AppMatcher.MatchTier.entries
                    .count { it in GumshoosConfig.matchOptions }
                    .let { on ->
                        if (on == AppMatcher.MatchTier.entries.size) {
                            "All ways of matching are on"
                        } else {
                            "$on of ${AppMatcher.MatchTier.entries.size} on"
                        }
                    },
                onClick = { onOpen(Screen.Matching) },
            )
            SettingsGroupDivider()
            CategoryRow(
                title = "Instant answers",
                summary = run {
                    val on = AnswerProviders.enabledIds.size
                    val total = AnswerProviders.all.size
                    val units = when (GumshoosConfig.unitSystemOverride) {
                        MeasurementSystem.SI -> "Metric"
                        MeasurementSystem.US, MeasurementSystem.UK -> "Imperial"
                        null -> "Device units"
                    }
                    val currency = GumshoosConfig.preferredCurrency ?: "device currency"
                    "$on of $total on · $units · $currency"
                },
                onClick = { onOpen(Screen.Answers) },
            )
            SettingsGroupDivider()
            CategoryRow(
                title = "Contacts",
                summary = when {
                    !hasContacts -> "Off — results won't include contacts"
                    GumshoosConfig.hiddenContacts.isEmpty() -> "On"
                    else -> "On · ${GumshoosConfig.hiddenContacts.size} hidden"
                },
                onClick = { onOpen(Screen.Contacts) },
            )
            SettingsGroupDivider()
            CategoryRow(
                title = "Web suggestions",
                summary = if (GumshoosConfig.webSuggestionsEnabled) {
                    "On · ${GumshoosConfig.webSuggestionSource.label}"
                } else {
                    "Off"
                },
                onClick = { onOpen(Screen.WebSuggestions) },
            )
            SettingsGroupDivider()
            CategoryRow(
                title = "Providers",
                summary = SearchProviders.enabled.let { shown ->
                    if (shown.isEmpty()) {
                        "None — the \"Search using\" row is hidden"
                    } else {
                        "${shown.size} shown · ${shown.first().label} first"
                    }
                },
                onClick = { onOpen(Screen.Providers) },
            )
        }
    }
}

/**
 * Which hand-off targets appear under "Search using", and in what order.
 *
 * Enabling and ordering share one screen because they are one question — what the row looks like —
 * and splitting them would mean scrolling the same list twice to arrange it.
 */
@Composable
internal fun ProvidersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ordered = SearchProviders.inConfiguredOrder(
        SearchProviders.all,
        GumshoosConfig.providerOrder,
    )

    // Availability is otherwise resolved once at warm time, so an app installed since then would
    // still read as missing on the one screen that talks about it. Re-read on every return to the
    // screen rather than once: installing happens in the Play Store, which we send the user to and
    // get no result back from, so a one-shot read leaves the Uninstalled group surviving the
    // install made from it.
    val unavailable = rememberOnResume { SearchProviders.refreshAvailabilityNow(context) }

    // Split rather than dimmed in place. A provider whose app is missing isn't a choice the row can
    // offer — it deliberately has no web fallback, so a greyed switch would be inviting a tap that
    // could never do anything. It moves to its own group with the one action that helps: install it.
    //
    // needsMissingApp, not a bare `id in unavailable`: a provider switched to a browser doesn't need
    // its app at all, so it belongs in the list above however absent that app is.
    val (installed, missing) = ordered.partition { !SearchProviders.needsMissingApp(it, unavailable) }

    // Which provider's options are open, by id rather than by value: the dialog writes config while
    // it is up, so holding the SearchProvider itself would pin a stale copy of nothing useful.
    var configuring by remember { mutableStateOf<String?>(null) }
    configuring?.let { id ->
        SearchProviders.all.firstOrNull { it.id == id }?.let { provider ->
            ProviderModeDialog(
                provider = provider,
                unavailable = unavailable,
                onDismiss = { configuring = null },
            )
        }
    }

    // A move reports positions among the rows on screen, which are only the installed ones, while
    // the order saved covers every provider. An uninstalled one keeps its place in that saved list,
    // so installing its app returns it to where it sat rather than to the end.
    //
    // Both the drag and the two accessibility actions land here, so a move of one place cannot come
    // out differently from a drag of one place.
    fun settle(from: Int, to: Int) {
        GumshoosConfig.setProviderOrder(
            context,
            SearchProviders.reorderVisible(
                ordered.map { it.id }, installed.map { it.id }, from, to,
            ),
        )
    }

    SettingsScaffold(title = "Providers", onBack = onBack) {
        SettingsNote(
            "The row that hands your query to another app or the web. " +
                "Enter goes to the first one when nothing else matches.",
        )

        // Undividered: this is one ordered list being arranged, and a line between each row would
        // cut the sequence a drag exists to change into six unrelated entries.
        SettingsGroup(note = "Drag a row by its handle to reorder.") {
            ReorderableColumn(
                list = installed,
                onSettle = ::settle,
                modifier = Modifier.fillMaxWidth(),
            ) { index, provider, isDragging ->
                // The item wrapper is what carries the row's drag offset and hands its content the
                // scope `draggableHandle` lives in; the row cannot reach it from the column lambda.
                ReorderableItem {
                    ProviderRow(
                        label = provider.label,
                        icon = provider.icon,
                        description = null,
                        checked = provider.id !in GumshoosConfig.disabledProviders,
                        enabled = true,
                        canMoveUp = index > 0,
                        canMoveDown = index < installed.lastIndex,
                        onCheckedChange = {
                            GumshoosConfig.setProviderEnabled(context, provider.id, it)
                        },
                        onMoveUp = { settle(index, index - 1) },
                        onMoveDown = { settle(index, index + 1) },
                        modifier = Modifier
                            .draggedRow(isDragging)
                            .padding(horizontal = SettingsInset),
                        dragHandle = Modifier.draggableHandle(),
                        onConfigure = { configuring = provider.id }
                            .takeIf { provider.canUseBrowser },
                        control = RowControl.CHECKBOX,
                    )
                }
            }
        }

        // A group of its own rather than a quieter heading inside the one above. These are not
        // further providers to arrange, they are ones this phone cannot offer at all.
        if (missing.isNotEmpty()) {
            SettingsGroup(
                title = "Uninstalled",
                note = "These need an app that isn't on this phone.",
            ) {
                missing.forEach { provider ->
                    provider.requiresPackage?.let {
                        MissingAppRow(
                            label = provider.label,
                            icon = provider.icon,
                            packageName = it,
                            // The only way to reach the browser choice for a provider whose app is
                            // gone: its ordinary row isn't drawn, and installing is otherwise the
                            // sole thing this group offers.
                            onConfigure = { configuring = provider.id }
                                .takeIf { provider.canUseBrowser },
                        )
                    }
                }
            }
        }

        // At the bottom, after every row that might send something to it — the picker is the answer
        // to a question the switches above raise, so it reads in that order.
        BrowserPicker()
    }
}

/**
 * Live completions from a search engine, and which engine.
 *
 * The switch and the engine share a screen because the engine is meaningless without the switch —
 * two rows on the hub would be one setting and its footnote.
 */
@Composable
internal fun WebSuggestionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled = GumshoosConfig.webSuggestionsEnabled

    SettingsScaffold(title = "Web suggestions", onBack = onBack) {
        SettingsNote(
            "Search engine completions for what you're typing, shown under the results.",
        )

        // The note is the point of the screen, not a caveat: this is the only feature here that
        // sends anything anywhere, and someone deciding has to be told before they decide, not
        // after. Stated as what happens rather than as a warning, because it isn't a malfunction.
        SettingsGroup(
            note = "Your query is sent to the engine below as you type. Nothing is sent until " +
                "you've typed ${WebSuggestions.MIN_QUERY_LENGTH} characters and paused.",
        ) {
            SettingsSwitchRow(
                title = "Show web suggestions",
                description = "Off by default.",
                checked = enabled,
                onCheckedChange = { GumshoosConfig.setWebSuggestionsEnabled(context, it) },
            )
        }

        // Hidden rather than disabled while off. A greyed pair of engines is a control over
        // something that isn't happening, and the switch above already says why it isn't.
        if (enabled) {
            SettingsGroup(title = "Suggestions from") {
                SuggestionSource.entries.forEach { source ->
                    SettingsRadioRow(
                        title = source.label,
                        description = "",
                        selected = GumshoosConfig.webSuggestionSource == source,
                        onClick = { GumshoosConfig.setWebSuggestionSource(context, source) },
                        icon = when (source) {
                            SuggestionSource.GOOGLE -> R.drawable.ic_provider_google
                            SuggestionSource.DUCKDUCKGO -> R.drawable.ic_provider_duckduckgo
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MatchingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val options = GumshoosConfig.matchOptions

    SettingsScaffold(title = "Matching", onBack = onBack) {
        SettingsNote(
            "Which ways of matching a query count. An app's full name and anything starting with " +
                "what you typed always match — those aren't listed, because search without them " +
                "isn't search.",
        )

        // No dividers between the four: they are one set of related toggles, not four separate
        // decisions, and a line between each would say otherwise.
        SettingsGroup(
            title = "Ways to match",
            note = "Switching one off doesn't score it lower, it stops it counting: with camel " +
                "case off, \"bms\" no longer pulls BookMyShow above an app actually starting " +
                "with those letters. Turning off skipped letters is the one that most reduces " +
                "stray results.",
        ) {
            AppMatcher.MatchTier.entries.forEach { tier ->
                SettingsSwitchRow(
                    title = tier.label,
                    description = tier.example,
                    checked = tier in options,
                    onCheckedChange = { GumshoosConfig.setMatchTierEnabled(context, tier, it) },
                )
            }
        }
    }
}

/**
 * Whether one provider opens its own app or hands the query to a browser.
 *
 * A dialog rather than a second switch on the row: the row's switch means "show this at all", and
 * two switches side by side would read as two halves of one setting. It writes as it is toggled and
 * closes on Done — there is nothing to cancel, and a Cancel that had to undo a live change would be
 * the only place in these screens that worked that way.
 */
@Composable
internal fun ProviderModeDialog(
    provider: SearchProvider,
    /** Availability as the screen last read it, so the warning agrees with the group the row is in. */
    unavailable: Set<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val browser = GumshoosConfig.opensInBrowser(provider.id)
    // Named, not described. "Your default browser" is what happens when nothing is chosen, and
    // saying so here is what connects this switch to the picker at the bottom of the screen.
    val chosen = GumshoosConfig.preferredBrowser
        ?.let { BrowserApps.labelFor(context, it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.label) },
        text = {
            Column {
                // No SettingsInset: the dialog already holds its content off the edge, and the
                // row's own would land the switch 44dp in.
                SettingsSwitchRow(
                    title = "Open in a browser",
                    description = if (browser) {
                        "Searches in ${chosen ?: "your default browser"}."
                    } else {
                        "Searches in the ${provider.label} app."
                    },
                    checked = browser,
                    onCheckedChange = {
                        GumshoosConfig.setOpensInBrowser(context, provider.id, it)
                    },
                    horizontalInset = 0.dp,
                )
                // Only reachable for a gated provider, which is the case this dialog exists to
                // rescue: the row is in the Uninstalled group and the switch is the way out of it.
                if (!browser && provider.id in unavailable) {
                    SettingsWarning(
                        "The ${provider.label} app isn't on this phone, so nothing appears under " +
                            "\"Search using\" until you install it or switch to a browser.",
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
internal fun AliasDialog(
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(label) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            // The field alone, with the explanation as its own supporting text. It used to be a
            // note above and a field below, which put the instructions before the thing they
            // describe and made the dialog two stacked blocks instead of one control.
            SettingsTextField(
                value = text,
                onValueChange = { text = it },
                // Says what to type, rather than showing two abbreviations of an app that may not
                // be the one on screen — "insta, ig" over a dialog headed Spotify read as junk.
                placeholder = "Aliases",
                supportingText = "Another name to find this app by. Separate several with commas, " +
                    "or empty the field to remove them.",
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ContactsScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    var hasContacts by remember { mutableStateOf(ContactsRepository.hasPermission(context)) }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasContacts = granted
        if (granted) {
            // Re-warm so contacts appear without waiting for the next boot.
            context.startForegroundService(
                OverlayService.intent(context, OverlayService.ACTION_WARM)
            )
        }
    }

    SettingsScaffold(title = "Contacts", onBack = onBack) {
        SettingsNote(AppPermission.CONTACTS.why)

        if (hasContacts) {
            SettingsGroup {
                CategoryRow(
                    title = "Hidden contacts",
                    summary = GumshoosConfig.hiddenContacts.size.let {
                        if (it == 0) "None hidden" else "$it hidden"
                    },
                    onClick = { onOpen(Screen.HiddenContacts) },
                )
            }
            EmailAppPicker()
        } else {
            // The grant is the only thing on the screen until it is made — the two controls below
            // both act on contacts there is no permission to read. It is a row rather than the
            // full-width button it was, so the one screen that has nothing else on it doesn't
            // shout, and `withoutIt` sits under it because "nothing else changes" is the reassurance
            // that makes an optional permission easy to decline.
            SettingsGroup(note = AppPermission.CONTACTS.withoutIt) {
                SettingsActionRow(
                    title = "Allow contacts access",
                    summary = "Optional, and asked for with a normal prompt.",
                    icon = R.drawable.ic_provider_terminal,
                    onClick = { request.launch(Manifest.permission.READ_CONTACTS) },
                )
            }
        }
    }
}

@Composable
internal fun AnswersScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AnswerProviders.enabledIds) }
    var unitSystem by remember { mutableStateOf(GumshoosConfig.unitSystemOverride) }

    SettingsScaffold(title = "Instant answers", onBack = onBack) {
        SettingsNote("Some queries answer themselves.")

        SettingsGroup(title = "Modules") {
            AnswerProviders.all.forEach { provider ->
                SettingsSwitchRow(
                    title = provider.label,
                    description = answerExample(provider.id),
                    checked = provider.id in enabled,
                    onCheckedChange = { on ->
                        enabled = if (on) enabled + provider.id else enabled - provider.id
                        GumshoosConfig.setAnswerProvidersEnabled(context, enabled)
                    },
                )
            }
        }

//        SettingsGroup(title = "Measurements") {
//            listOf(
//                null to "Follow device",
//                MeasurementSystem.SI to "Metric",
//                MeasurementSystem.US to "Imperial",
//            ).forEach { (option, label) ->
//                SettingsRadioRow(
//                    title = label,
//                    description = when (option) {
//                        null -> "Whatever your phone's region implies."
//                        MeasurementSystem.SI -> "5ft answers in metres."
//                        else -> "5m answers in feet."
//                    },
//                    selected = unitSystem == option,
//                    onClick = {
//                        unitSystem = option
//                        GumshoosConfig.setUnitSystemOverride(context, option)
//                    },
//                )
//            }
//        }
//
//        // The note lands after the last of the three defaults rather than ahead of the first,
//        // because it is one statement about all of them.
//        SettingsGroup {
//            CategoryRow(
//                title = "Currency",
//                summary = GumshoosConfig.preferredCurrency ?: "Follow device",
//                onClick = { onOpen(Screen.CurrencyPicker) },
//            )
//            SettingsGroupDivider()
//            CategoryRow(
//                title = "Time zone",
//                summary = GumshoosConfig.preferredZone ?: "Follow device",
//                onClick = { onOpen(Screen.ZonePicker) },
//            )
//        }

        CurrencyRatesSettings()
    }
}

private fun answerExample(providerId: String): String = when (providerId) {
    "math" -> "2+2, 10% of 50, sqrt 16"
    "units" -> "5ft, 100 km/h to mph, 499 min"
    "bases" -> "0x1F, 255 to binary"
    "time" -> "time in tokyo, 3pm IST to PST"
    "currency" -> "\$5, 500 inr to usd"
    else -> ""
}

/**
 * Every way of getting search open: the drawer icon, the assistant gesture, and the things that can
 * be placed on the home screen.
 *
 * A top-level destination rather than a corner of Advanced, which is where it began. The launcher
 * icon deliberately opens settings rather than search, so "how do I actually bring this up" is the
 * question a fresh install arrives with — and the answer was three levels down.
 *
 * The overlay permission is only reported here, never granted. Two buttons onto the same system
 * dialog is two places for the explanation to drift; Permissions owns that.
 */
@Composable
internal fun LaunchingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hasOverlay = rememberPermission { Settings.canDrawOverlays(context) }

    SettingsScaffold(title = "Launching", onBack = onBack) {
        // Granting lives on the Permissions screen. What this screen owes the reader is a reason
        // why everything it offers would be inert.
        if (!hasOverlay) {
            SettingsWarning("Search can't open until \"draw over other apps\" is granted.")
        }

        DrawerIconSettings()
        AssistSettings()
        AddToHomeScreenSettings()
    }
}

@Composable
internal fun BehaviourScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hasUsage = rememberPermission { UsageRepository.hasPermission(context) }

    SettingsScaffold(title = "Behaviour", onBack = onBack) {
        UsageSourcePicker(hasUsage = hasUsage)

        SettingsGroup(
            title = "New apps tab",
            note = "Controls how long to show app installs and updates in the New tab",
        ) {
            SettingsSliderRow(
                label = "Show for",
                // The slider stops on 3, 6, 9 and 12 only; a value saved by an older build can sit
                // outside that, so snap it in rather than showing the thumb pinned at an end.
                value = GumshoosConfig.newUpdatedWindowHours.toInt().coerceIn(3, 12),
                min = 3,
                max = 12,
                step = 3,
                valueText = { "$it hours" },
                onValue = {
                    GumshoosConfig.setNewUpdatedWindowHours(context, it.toLong())
                },
            )
        }

        // The note goes under the card, like every other group's. It is a caveat rather than an
        // instruction, and the honest version is short: we ask, the keyboard decides, and nothing
        // tells us which happened.
        SettingsGroup(title = "Privacy") {
            SettingsSwitchRow(
                title = "Private keyboard",
                description = "Asks your keyboard not to learn from what you type here. ",
                checked = GumshoosConfig.incognitoKeyboard,
                onCheckedChange = { GumshoosConfig.setIncognitoKeyboard(context, it) },
            )
        }
    }
}

@Composable
internal fun AdvancedScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hideOnFocusLoss by remember { mutableStateOf(GumshoosConfig.hideOnFocusLoss) }

    SettingsScaffold(title = "Advanced", onBack = onBack) {
        // Separated by rules as well as by card, unlike every other screen here. Advanced is the one
        // list whose sections are unrelated to each other — a launch preference, two recovery
        // actions and a pair of window settings share only that none of them belongs anywhere else
        // — so the line is doing the job it exists for rather than fencing off one set of choices.
        SettingsGroup(
            title = "Opening apps",
            note = "Only worth changing if an app won't open.",
        ) {
            LaunchMethod.entries.forEach { method ->
                SettingsRadioRow(
                    title = method.label,
                    description = method.description,
                    selected = GumshoosConfig.launchMethod == method,
                    onClick = { GumshoosConfig.setLaunchMethod(context, method) },
                )
            }
        }

        SettingsDivider()

        SettingsGroup(title = "Maintenance") {
            IndexRefresh()
            SettingsGroupDivider()
            WidgetRefresh()
        }

        SettingsDivider()

        SettingsGroup {
            SettingsSwitchRow(
                // "when focus is lost" was the mechanism, and it is no longer even the whole one —
                // the navigation gestures are caught a different way. The setting key is unchanged.
                title = "Close when you switch away",
                description = "Closes search when you go home, open recents, or open another app.",
                checked = hideOnFocusLoss,
                onCheckedChange = {
                    hideOnFocusLoss = it
                    GumshoosConfig.setHideOnFocusLoss(context, it)
                },
            )
            SettingsGroupDivider()
            SettingsActionRow(
                title = "Open notification settings",
                // The mechanism stays here: a foreground service must supply a notification and
                // cannot suppress it from inside, but Android 13+ only *displays* it when
                // POST_NOTIFICATIONS is granted — which is why the app never asks for it, and why
                // denying it is the supported way to run the service quietly. See Permissions.kt.
                summary = "Gumshoos doesn't need notifications. Turning them off is recommended.",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                },
            )
        }

        // No divider of its own: it draws nothing at all when there is no crash, which is almost
        // always, and a rule with nothing under it would be the visible half of that.
        CrashReport()

        // The divider is inside the gate for the same reason — in a release build it would be a
        // line closing the screen with nothing beneath it.
        //
        // The crash card is not gated: it hides itself, and a user who can copy a trace and send it
        // is worth having. The event trace is developer instrumentation, a monospace log dump with
        // no meaning to anyone else, so it ships only in debug. EventLog still records in release —
        // the recording is what makes a crash report readable; only the viewer goes away.
        if (BuildConfig.DEBUG) {
            SettingsDivider()
            EventTrace()
        }
    }
}

@Composable
internal fun AppearanceScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(GumshoosConfig.animationMode) }
    val systemDisabled = GumshoosConfig.systemAnimationScale(context) == 0f

    SettingsScaffold(title = "Appearance", onBack = onBack) {
        // Two groups, because they are two places. The widgets are drawn on the home screen by the
        // launcher; the window is drawn over whatever is already there. Merging them into one list
        // of "things to restyle" lost that.
        SettingsGroup(title = "Overlay") {
            CategoryRow(
                title = "Search window",
                summary = searchWindowSummary(),
                onClick = { onOpen(Screen.SearchWindow) },
            )
        }

        SettingsGroup(title = "Home screen") {
            CategoryRow(
                title = "App icon widget",
                summary = "Icon ${GumshoosConfig.widgetIconSizeDp}dp" +
                    if (GumshoosConfig.widgetIconOffsetDp == 0) {
                        ""
                    } else {
                        " · offset ${GumshoosConfig.widgetIconOffsetDp}dp"
                    },
                onClick = { onOpen(Screen.WidgetIcon) },
            )
            SettingsGroupDivider()
            CategoryRow(
                title = "Search bar widget",
                summary = widgetBarSummary(),
                onClick = { onOpen(Screen.WidgetBar) },
            )
        }

        SettingsGroup(
            title = "Animations",
            note = "If you prefer an even snappier experience; recommended for lower-end phones.",
        ) {
            AnimationMode.entries.forEach { option ->
                SettingsRadioRow(
                    title = when (option) {
                        AnimationMode.FOLLOW_SYSTEM -> "Follow system setting"
                        AnimationMode.ALWAYS_OFF -> "Always off"
                    },
                    description = when (option) {
                        AnimationMode.FOLLOW_SYSTEM ->
                            if (systemDisabled) {
                                "Animations are disabled system-wide currently."
                            } else {
                                "Animations are enabled system-wide currently."
                            }
                        AnimationMode.ALWAYS_OFF -> "The window and its sections appear instantly."
                    },
                    selected = mode == option,
                    onClick = {
                        mode = option
                        GumshoosConfig.setAnimationMode(context, option)
                    },
                )
            }
        }
    }
}

private fun searchWindowSummary(): String {
    val style = GumshoosConfig.searchWindowStyle
    val colour = if (style.adaptive) "Follows theme" else "Custom colour"
    val opacity = if (style.alphaPercent < GumshoosConfig.WINDOW_ALPHA_OPAQUE) {
        "transparent"
    } else {
        "opaque"
    }
    return "$colour · $opacity · ${style.cornerRadiusDp}dp corners"
}

/** Leads with the shape, since that is what distinguishes one bar from another at a glance. */
private fun widgetBarSummary(): String {
    val style = GumshoosConfig.widgetBarStyle
    val shape = when {
        style.wireframe -> "Outline"
        style.cornerPercent >= 95 -> "Pill"
        style.cornerPercent <= 5 -> "Square"
        else -> "Rounded"
    }
    val actions = GumshoosConfig.widgetBarActions.size
    return "$shape · ${style.logo.label} · $actions action${if (actions == 1) "" else "s"}"
}

@Composable
internal fun CurrencyPickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // The rate table is the honest list: a currency it doesn't carry cannot be converted to.
    val codes = CurrencyRates.table?.let { table ->
        (table.rates.keys + table.base).sorted()
    } ?: emptyList()

    SearchablePicker(
        title = "Default currency",
        items = codes.map { code ->
            PickerItem(
                id = code,
                title = code,
                subtitle = runCatching { Currency.getInstance(code).displayName }.getOrNull(),
            )
        },
        selectedId = GumshoosConfig.preferredCurrency,
        defaultLabel = "Follow device",
        defaultDescription = "Uses the currency your phone's region implies.",
        filterPlaceholder = "Search currencies",
        onPick = { GumshoosConfig.setPreferredCurrency(context, it); onBack() },
        onBack = onBack,
    )
}

@Composable
internal fun ZonePickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val zones = remember {
        ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .sorted()
    }

    SearchablePicker(
        title = "Default time zone",
        items = zones.map { id ->
            PickerItem(
                id = id,
                title = id.substringAfterLast('/').replace('_', ' '),
                subtitle = id,
            )
        },
        selectedId = GumshoosConfig.preferredZone,
        defaultDescription = "Uses whatever your phone is set to.",
        defaultLabel = "Follow device",
        filterPlaceholder = "Search time zones",
        onPick = { GumshoosConfig.setPreferredZone(context, it); onBack() },
        onBack = onBack,
    )
}
