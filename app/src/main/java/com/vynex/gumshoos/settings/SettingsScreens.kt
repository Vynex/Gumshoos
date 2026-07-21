package com.vynex.gumshoos.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.vynex.gumshoos.AssistIntegration
import com.vynex.gumshoos.ContactsRepository
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.LaunchMethod
import com.vynex.gumshoos.OverlayService
import com.vynex.gumshoos.SearchProviders
import com.vynex.gumshoos.SearchShortcuts
import com.vynex.gumshoos.UsageRepository
import com.vynex.gumshoos.WidgetActionActivity
import com.vynex.gumshoos.answers.AnswerProviders
import com.vynex.gumshoos.answers.CurrencyRates
import com.vynex.gumshoos.answers.MeasurementSystem
import java.time.ZoneId
import java.util.Currency

/**
 * Re-reads something whenever the screen comes back to the front, because it is changed elsewhere
 * — in a system settings screen we sent the user to, and can't get a result back from.
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
                fullBleed = false,
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
                fullBleed = false,
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
                fullBleed = false,
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
                fullBleed = false,
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
    // still read "Not installed" on the one screen that talks about it.
    LaunchedEffect(Unit) { SearchProviders.refreshAvailability(context) }

    fun move(id: String, delta: Int) {
        // The order is persisted in full from what's on screen. Saving only the moved id would
        // leave the rest implicit, and "unlisted sorts last" would then scramble them.
        GumshoosConfig.setProviderOrder(
            context,
            SearchProviders.reorder(ordered.map { it.id }, id, delta),
        )
    }

    SettingsScaffold(title = "Providers", onBack = onBack) {
        SettingsNote(
            "The row of marks under \"Search using\", shown once a query matches no installed " +
                "app. The first one is also where Enter goes when nothing matched.",
        )

        // Undividered: this is one ordered list being arranged, and a line between each row would
        // cut the sequence the arrows exist to change into six unrelated entries.
        SettingsGroup(
            note = "Spotify and ChatGPT appear only when their app is installed — searching " +
                "either on the web lands on a login wall rather than a result.",
        ) {
            ordered.forEachIndexed { index, provider ->
                val isMissing = provider.id in SearchProviders.missing
                ProviderRow(
                    label = provider.label,
                    icon = provider.icon,
                    description = when {
                        isMissing -> "Not installed, so it stays hidden"
                        else -> null
                    },
                    checked = provider.id !in GumshoosConfig.disabledProviders,
                    enabled = !isMissing,
                    canMoveUp = index > 0,
                    canMoveDown = index < ordered.lastIndex,
                    onCheckedChange = {
                        GumshoosConfig.setProviderEnabled(context, provider.id, it)
                    },
                    onMoveUp = { move(provider.id, -1) },
                    onMoveDown = { move(provider.id, 1) },
                    modifier = Modifier.padding(horizontal = SettingsInset),
                )
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
                    fullBleed = false,
                )
            }
        }
    }
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
            Column {
                SettingsNote("Comma separated. Clearing the field removes them.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Also matches") },
                    placeholder = { Text("insta, ig") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
        SettingsNote(
            if (hasContacts) {
                "Granted. ${AppPermission.CONTACTS.why}"
            } else {
                "Optional. ${AppPermission.CONTACTS.withoutIt}"
            },
        )
        Button(
            onClick = { request.launch(Manifest.permission.READ_CONTACTS) },
            enabled = !hasContacts,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Allow contacts access") }

        if (hasContacts) {
            SettingsDivider()
            CategoryRow(
                title = "Hidden contacts",
                summary = GumshoosConfig.hiddenContacts.size.let {
                    if (it == 0) "None hidden" else "$it hidden"
                },
                onClick = { onOpen(Screen.HiddenContacts) },
            )

            SettingsDivider()
            EmailAppPicker()
        }
    }
}

@Composable
internal fun AnswersScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AnswerProviders.enabledIds) }
    var unitSystem by remember { mutableStateOf(GumshoosConfig.unitSystemOverride) }

    SettingsScaffold(title = "Instant answers", onBack = onBack) {
        SettingsNote(
            "Some queries answer themselves. Typing 2+2, 5ft, 0x1F, \$5 or \"time in tokyo\" " +
                "shows a result above the contacts section.",
        )

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
                    fullBleed = false,
                )
            }
        }

        SettingsGroup(title = "Measurements") {
            listOf(
                null to "Follow device",
                MeasurementSystem.SI to "Metric",
                MeasurementSystem.US to "Imperial",
            ).forEach { (option, label) ->
                SettingsRadioRow(
                    title = label,
                    description = when (option) {
                        null -> "Whatever your phone's region implies."
                        MeasurementSystem.SI -> "5ft answers in metres."
                        else -> "5m answers in feet."
                    },
                    selected = unitSystem == option,
                    onClick = {
                        unitSystem = option
                        GumshoosConfig.setUnitSystemOverride(context, option)
                    },
                    fullBleed = false,
                )
            }
        }

        // The note lands after the last of the three defaults rather than ahead of the first,
        // because it is one statement about all of them.
        SettingsGroup(
            note = "These are what a conversion becomes when the query doesn't say. Writing the " +
                "target explicitly — \"5ft to inches\", \"\$5 to CAD\" — always wins over them.",
        ) {
            CategoryRow(
                title = "Currency",
                summary = GumshoosConfig.preferredCurrency ?: "Follow device",
                onClick = { onOpen(Screen.CurrencyPicker) },
                fullBleed = false,
            )
            SettingsGroupDivider()
            CategoryRow(
                title = "Time zone",
                summary = GumshoosConfig.preferredZone ?: "Follow device",
                onClick = { onOpen(Screen.ZonePicker) },
                fullBleed = false,
            )
        }

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
 * How search gets opened: keeping the process warm, and the launcher targets. Lives under Advanced
 * — none of it needs touching once set up, and the widget is the intended way in.
 *
 * The overlay permission is only reported here, never granted. Two buttons onto the same system
 * dialog is two places for the explanation to drift; Permissions owns that.
 */
@Composable
internal fun LaunchingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hasOverlay = rememberPermission { Settings.canDrawOverlays(context) }

    SettingsScaffold(title = "Launching Gumshoos", onBack = onBack) {
        // Granting lives on the Permissions screen, not here. What this screen still owes the user
        // is a reason for the dead button below, which is disabled without the permission.
        if (!hasOverlay) {
            SettingsWarning(
                "Nothing here works until \"draw over other apps\" is granted, under Permissions.",
            )
        }

        SettingsSection("Keep it warm")
        SettingsNote(
            "Starts the service and pre-attaches the window, which is what makes opening search " +
                "near-instant. Needed once per boot; placing the widget does it too.",
        )
        Button(
            onClick = {
                context.startForegroundService(
                    OverlayService.intent(context, OverlayService.ACTION_WARM)
                )
            },
            enabled = hasOverlay,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start & pre-warm") }

        SettingsDivider()
        SearchIconSettings()

        SettingsDivider()
        AssistSettings()
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
            note = "An app shows up there for this long after it's installed or updated.",
        ) {
            SettingsSliderRow(
                label = "Counts as new for",
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
    }
}

@Composable
internal fun AdvancedScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    val hasOverlay = rememberPermission { Settings.canDrawOverlays(context) }
    var hideOnFocusLoss by remember { mutableStateOf(GumshoosConfig.hideOnFocusLoss) }

    SettingsScaffold(title = "Advanced", onBack = onBack) {
        SettingsSwitchRow(
            title = "Close when focus is lost",
            description = "Dismisses the search window when another app takes over — tapping a " +
                "notification, or pulling down the shade. This also covers the Home gesture, " +
                "which reports late and so feels sluggish.",
            checked = hideOnFocusLoss,
            onCheckedChange = {
                hideOnFocusLoss = it
                GumshoosConfig.setHideOnFocusLoss(context, it)
            },
        )

        SettingsDivider()
        CategoryRow(
            title = "Launching Gumshoos",
            summary = when {
                !hasOverlay -> "Overlay permission needed"
                AssistIntegration.isCurrentAssistant(context) == true ->
                    "Widget · assistant gesture on"
                SearchShortcuts.isDrawerIconEnabled(context) -> "Widget · second app icon on"
                else -> "Widget, quick tile and shortcuts"
            },
            onClick = { onOpen(Screen.Launching) },
        )

        SettingsDivider()
        SettingsSection("How apps are opened")
        SettingsNote(
            "A diagnostic lever, not a preference. Some launchers reopen the app you just left " +
                "when you tap the widget mid-transition — a trace showed that tap never reaching " +
                "Gumshoos at all, so this most likely can't fix it, but it makes the alternatives " +
                "cheap to rule out. Each launch records which method it used in the event trace.",
        )
        LaunchMethod.entries.forEach { method ->
            SettingsRadioRow(
                title = method.label,
                description = method.description,
                selected = GumshoosConfig.launchMethod == method,
                onClick = { GumshoosConfig.setLaunchMethod(context, method) },
            )
        }

        SettingsDivider()
        SettingsSection("Notification")
        SettingsNote(
            "A foreground service must supply a notification — the system won't let it run " +
                "without one, and it can't be suppressed from inside the app. On Android 13+ the " +
                "system only shows it when notification permission is granted, so switching " +
                "notifications off hides it while the service keeps running. Gumshoos will still " +
                "be listed under \"Active apps\" in the shade; that part is unavoidable.",
        )
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open notification settings") }

        SettingsDivider()
        IndexRefresh()

        SettingsDivider()
        WidgetRefresh()

        SettingsDivider()
        SettingsNote(
            "Diagnostics. The event trace records what the overlay did and when, which is how " +
                "the last few misbehaviours were pinned down without a debugger attached.",
        )

        SettingsDivider()
        CrashReport()
        EventTrace()

        SettingsDivider()
        SettingsSection("Google entry points")
        SettingsNote(
            "Lists what the Google apps expose for voice search and Lens on this device, into the " +
                "trace above. Neither has a published intent, and the ones that look right don't " +
                "behave as their names suggest — voice search turned out to funnel five different " +
                "actions into the same assistant hand-off. These read the answer off the device " +
                "instead of guessing at component names.",
        )
        OutlinedButton(
            onClick = { WidgetActionActivity.probeVoiceTargets(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Probe voice search targets") }
        OutlinedButton(
            onClick = { WidgetActionActivity.probeLensTargets(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Probe Lens targets") }

        SettingsDivider()
        SettingsNote(
            "The probes say what exists and where an intent resolves. Neither can say whether " +
                "anything appeared — a component that opens and closes itself again reports " +
                "success — and that is what four wrong Lens entry points have had in common. The " +
                "tester fires one candidate at a time so it can be watched.",
        )
        CategoryRow(
            title = "Entry point tester",
            summary = "Fire one Lens or voice target at a time",
            onClick = { onOpen(Screen.EntryPoints) },
        )

        SettingsDivider()
        SettingsSection("Compare launch paths")
        SettingsNote(
            "Opens the overlay from here, which goes through an activity and is deliberately the " +
                "slow path. Compare it against tapping the widget.",
        )
        OutlinedButton(
            onClick = {
                context.startForegroundService(
                    OverlayService.intent(context, OverlayService.ACTION_SHOW, source = "in-app")
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Show overlay from app") }
    }
}

@Composable
internal fun AppearanceScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(GumshoosConfig.animationMode) }
    val systemDisabled = GumshoosConfig.systemAnimationScale(context) == 0f

    SettingsScaffold(title = "Appearance", onBack = onBack) {
        SettingsSection("Widgets")
        CategoryRow(
            title = "Search bar widget",
            summary = widgetBarSummary(),
            onClick = { onOpen(Screen.WidgetBar) },
        )
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

        SettingsDivider()
        SettingsSection("Search")
        CategoryRow(
            title = "Search window",
            summary = searchWindowSummary(),
            onClick = { onOpen(Screen.SearchWindow) },
        )

        SettingsDivider()
        SettingsSection("Animations")
        SettingsNote(
            "The search window opens in about 20ms; the animation is what makes that legible " +
                "rather than abrupt. Turning it off makes the window appear instantly.",
        )

        AnimationMode.entries.forEach { option ->
            SettingsRadioRow(
                title = when (option) {
                    AnimationMode.FOLLOW_SYSTEM -> "Follow system setting"
                    AnimationMode.ALWAYS_ON -> "Always on"
                    AnimationMode.ALWAYS_OFF -> "Always off"
                },
                description = when (option) {
                    AnimationMode.FOLLOW_SYSTEM ->
                        if (systemDisabled) {
                            "Your phone currently has animations turned off, so this means off."
                        } else {
                            "Your phone currently has animations turned on."
                        }
                    AnimationMode.ALWAYS_ON ->
                        "Except when the phone has animations disabled system-wide — Compose's own " +
                            "clock honours that, and nothing here can override it."
                    AnimationMode.ALWAYS_OFF -> "Window and sections appear instantly."
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
