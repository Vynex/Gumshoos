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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vynex.gumshoos.AnimationMode
import com.vynex.gumshoos.AppEntry
import com.vynex.gumshoos.AppMatcher
import com.vynex.gumshoos.AppPermission
import com.vynex.gumshoos.AppRepository
import com.vynex.gumshoos.ContactsRepository
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.OverlayService
import com.vynex.gumshoos.SearchProviders
import com.vynex.gumshoos.SearchShortcuts
import com.vynex.gumshoos.UsageRepository
import com.vynex.gumshoos.UsageSource
import com.vynex.gumshoos.WidgetActionActivity
import com.vynex.gumshoos.answers.AnswerProviders
import com.vynex.gumshoos.answers.CurrencyRates
import com.vynex.gumshoos.answers.MeasurementSystem
import java.time.ZoneId
import java.util.Currency
import kotlin.math.roundToInt

/** Re-reads a permission whenever the screen comes back to the front, since it's granted elsewhere. */
@Composable
private fun rememberPermission(check: () -> Boolean): Boolean {
    var granted by remember { mutableStateOf(check()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = check()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

@Composable
internal fun AppsScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    val hasUsage = rememberPermission { UsageRepository.hasPermission(context) }

    SettingsScaffold(title = "Apps", onBack = onBack) {
        SettingsSection("Frequent & Recent ordering")
        UsageSourcePicker()

        Spacer(Modifier.height(8.dp))
        SettingsNote(
            when {
                GumshoosConfig.usageSource == UsageSource.GUMSHOOS ->
                    "Usage access isn't used with this option and can be revoked."
                hasUsage -> "Granted. Apps are ordered by system-wide usage."
                else ->
                    "Not granted. ${AppPermission.USAGE.withoutIt} It's a special access grant " +
                        "rather than a normal prompt."
            },
            isWarning = GumshoosConfig.usageSource == UsageSource.SYSTEM && !hasUsage,
        )
        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            enabled = !hasUsage && GumshoosConfig.usageSource == UsageSource.SYSTEM,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grant usage access") }

        SettingsDivider()
        SettingsSection("NEW/UPDATED tab")
        var windowHours by remember {
            mutableStateOf(GumshoosConfig.newUpdatedWindowHours.toFloat())
        }
        SettingsNote(
            "How recently an app must have been installed or updated to appear there: " +
                "${windowHours.roundToInt()} hours.",
        )
        Slider(
            value = windowHours,
            onValueChange = { windowHours = it },
            onValueChangeFinished = {
                GumshoosConfig.setNewUpdatedWindowHours(context, windowHours.roundToInt().toLong())
            },
            valueRange = 1f..168f,
            modifier = Modifier.fillMaxWidth(),
        )

        SettingsDivider()
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
        CategoryRow(
            title = "Custom names",
            summary = GumshoosConfig.appAliases.size.let {
                if (it == 0) "None set" else "$it app${if (it == 1) "" else "s"} renamed"
            },
            onClick = { onOpen(Screen.CustomNames) },
        )
        CategoryRow(
            title = "Hidden apps",
            summary = GumshoosConfig.hiddenApps.size.let {
                if (it == 0) "None hidden" else "$it hidden"
            },
            onClick = { onOpen(Screen.HiddenApps) },
        )
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

    SettingsScaffold(title = "Search providers", onBack = onBack) {
        SettingsNote(
            "The row of marks under \"Search using\", shown once a query matches no installed " +
                "app. The first one is also where Enter goes when nothing matched.",
        )

        SettingsSection("Providers")
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
            )
        }

        SettingsDivider()
        SettingsNote(
            "Spotify and ChatGPT appear only when their app is installed — searching either " +
                "on the web lands on a login wall rather than a result.",
        )
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

        SettingsSection("Ways to match")
        AppMatcher.MatchTier.entries.forEach { tier ->
            SettingsSwitchRow(
                title = tier.label,
                description = tier.example,
                checked = tier in options,
                onCheckedChange = { GumshoosConfig.setMatchTierEnabled(context, tier, it) },
            )
        }

        SettingsDivider()
        SettingsNote(
            "Switching one off doesn't score it lower, it stops it counting: with camel case off, " +
                "\"bms\" no longer pulls BookMyShow above an app actually starting with those " +
                "letters. Turning off skipped letters is the one that most reduces stray results.",
        )
    }
}

/**
 * Per-app extra names.
 *
 * Presented over the whole app list rather than as a list of the ones already renamed, because the
 * task always starts from "this app" — the alternative is an add button that opens a second picker.
 */
@Composable
internal fun CustomNamesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<AppEntry?>(null) }

    editing?.let { entry ->
        AliasDialog(
            label = entry.label,
            initial = GumshoosConfig.appAliases[entry.packageName].orEmpty(),
            onDismiss = { editing = null },
            onConfirm = { text ->
                GumshoosConfig.setAppAliases(context, entry.packageName, text)
                editing = null
            },
        )
    }

    AppListPicker(
        title = "Custom names",
        header = "Extra names an app answers to, on top of its real one. Separate several with " +
            "commas. Results still show the app's real name.",
        emptyNote = "No apps indexed yet. Open search once and come back.",
        items = AppRepository.apps.sortedBy { it.label.lowercase() }.map { entry ->
            PickerItem(
                id = entry.packageName,
                title = entry.label,
                subtitle = GumshoosConfig.appAliases[entry.packageName],
                icon = entry.icon,
            )
        },
        onClick = { packageName ->
            editing = AppRepository.apps.firstOrNull { it.packageName == packageName }
        },
        onBack = onBack,
    )
}

@Composable
private fun AliasDialog(
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
internal fun HiddenAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hidden = GumshoosConfig.hiddenApps

    MultiSelectPicker(
        title = "Hidden apps",
        header = "Checked apps never appear in results, in search or the tabs.",
        emptyNote = "No apps indexed yet. Open search once and come back.",
        items = AppRepository.apps.map {
            PickerItem(id = it.packageName, title = it.label, subtitle = it.packageName, icon = it.icon)
        },
        selected = hidden,
        onToggle = { packageName, hide -> GumshoosConfig.setAppHidden(context, packageName, hide) },
        onBack = onBack,
    )
}

@Composable
internal fun HiddenContactsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    MultiSelectPicker(
        title = "Hidden contacts",
        header = "Checked contacts never appear in results.",
        emptyNote = "No contacts loaded. Grant contacts access and open search once.",
        items = ContactsRepository.contacts.map {
            PickerItem(id = it.lookupKey, title = it.name, subtitle = it.phone ?: it.email)
        },
        selected = GumshoosConfig.hiddenContacts,
        onToggle = { key, hide -> GumshoosConfig.setContactHidden(context, key, hide) },
        onBack = onBack,
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

        SettingsSection("Modules")
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

        SettingsDivider()
        SettingsSection("Defaults")
        SettingsNote(
            "What a conversion becomes when the query doesn't say. Writing the target explicitly " +
                "— \"5ft to inches\", \"\$5 to CAD\" — always wins over these.",
        )

        Spacer(Modifier.height(8.dp))
        Text("Measurements", style = MaterialTheme.typography.bodyMedium)
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
            )
        }

        SettingsDivider()
        CategoryRow(
            title = "Currency",
            summary = GumshoosConfig.preferredCurrency ?: "Follow device",
            onClick = { onOpen(Screen.CurrencyPicker) },
        )
        CategoryRow(
            title = "Time zone",
            summary = GumshoosConfig.preferredZone ?: "Follow device",
            onClick = { onOpen(Screen.ZonePicker) },
        )

        SettingsDivider()
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
            SettingsNote(
                "Nothing here works until \"draw over other apps\" is granted, under Permissions.",
                isWarning = true,
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
    }
}

@Composable
internal fun BehaviourScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hideOnFocusLoss by remember { mutableStateOf(GumshoosConfig.hideOnFocusLoss) }

    SettingsScaffold(title = "Behaviour", onBack = onBack) {
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
    }
}

@Composable
internal fun AdvancedScreen(onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    val hasOverlay = rememberPermission { Settings.canDrawOverlays(context) }

    SettingsScaffold(title = "Advanced", onBack = onBack) {
        CategoryRow(
            title = "Launching Gumshoos",
            summary = when {
                !hasOverlay -> "Overlay permission needed"
                SearchShortcuts.isDrawerIconEnabled(context) -> "Widget · second app icon on"
                else -> "Widget, quick tile and shortcuts"
            },
            onClick = { onOpen(Screen.Launching) },
        )

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
        SettingsSection("Voice search targets")
        SettingsNote(
            "Lists what the Google app exposes for voice search on this device, into the trace " +
                "above. The intent that ought to be right lands on a hands-free activity that " +
                "hands off to the assistant, so the working entry point has to be read off the " +
                "device rather than reasoned about.",
        )
        OutlinedButton(
            onClick = { WidgetActionActivity.probeVoiceTargets(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Probe voice search targets") }

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

        SettingsDivider()
        SettingsSection("Widgets")
        CategoryRow(
            title = "Search bar widget",
            summary = widgetBarSummary(),
            onClick = { onOpen(Screen.WidgetBar) },
        )

        SettingsDivider()
        WidgetTuning()
    }
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
        onPick = { GumshoosConfig.setPreferredZone(context, it); onBack() },
        onBack = onBack,
    )
}
