package com.vynex.gumshoos.settings

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.vynex.gumshoos.ContactsRepository
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.SearchProviders
import com.vynex.gumshoos.UsageRepository
import com.vynex.gumshoos.UsageSource
import com.vynex.gumshoos.answers.AnswerProviders
import com.vynex.gumshoos.answers.CurrencyRates
import com.vynex.gumshoos.answers.MeasurementSystem

/**
 * The settings root.
 *
 * Each row carries a summary of its own state, so the question "what is this set to" is answered
 * without opening anything.
 */
@Composable
internal fun SettingsHome(onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasUsage by remember { mutableStateOf(UsageRepository.hasPermission(context)) }
    var hasContacts by remember { mutableStateOf(ContactsRepository.hasPermission(context)) }

    // Permissions are granted in other apps, so re-read whenever we come back to the front.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                hasUsage = UsageRepository.hasPermission(context)
                hasContacts = ContactsRepository.hasPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text("Gumshoos", style = MaterialTheme.typography.headlineMedium)

            if (!hasOverlay) {
                Spacer(Modifier.height(8.dp))
                SettingsNote(
                    "Search can't open without permission to draw over other apps. " +
                        "Grant it under Launching Gumshoos.",
                    isWarning = true,
                )
            }
            Spacer(Modifier.height(12.dp))

            CategoryRow(
                title = "Apps",
                summary = appsSummary(hasUsage),
                onClick = { onOpen(Screen.Apps) },
            )
            CategoryRow(
                title = "Contacts",
                summary = when {
                    !hasContacts -> "Off — results won't include contacts"
                    GumshoosConfig.hiddenContacts.isEmpty() -> "On"
                    else -> "On · ${GumshoosConfig.hiddenContacts.size} hidden"
                },
                onClick = { onOpen(Screen.Contacts) },
            )
            CategoryRow(
                title = "Instant answers",
                summary = answersSummary(),
                onClick = { onOpen(Screen.Answers) },
            )
            CategoryRow(
                title = "Launching Gumshoos",
                summary = launchingSummary(hasOverlay, SearchShortcuts.isDrawerIconEnabled(context)),
                onClick = { onOpen(Screen.Launching) },
            )
            CategoryRow(
                title = "Appearance & motion",
                summary = when (GumshoosConfig.animationMode) {
                    AnimationMode.FOLLOW_SYSTEM -> "Animations follow system"
                    AnimationMode.ALWAYS_ON -> "Animations always on"
                    AnimationMode.ALWAYS_OFF -> "Animations off"
                },
                onClick = { onOpen(Screen.Appearance) },
            )
            CategoryRow(
                title = "Behaviour",
                summary = if (GumshoosConfig.hideOnFocusLoss) {
                    "Closes when focus is lost"
                } else {
                    "Stays open until dismissed"
                },
                onClick = { onOpen(Screen.Behaviour) },
            )

            SettingsDivider()

            CategoryRow(
                title = "Advanced",
                summary = "Diagnostics and troubleshooting",
                onClick = { onOpen(Screen.Advanced) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun appsSummary(hasUsage: Boolean): String {
    val ordering = when {
        GumshoosConfig.usageSource == UsageSource.GUMSHOOS -> "Ordered by launches from Gumshoos"
        hasUsage -> "Ordered by system-wide usage"
        else -> "Alphabetical — usage access not granted"
    }
    val hidden = GumshoosConfig.hiddenApps.size
    return if (hidden == 0) ordering else "$ordering · $hidden hidden"
}

private fun answersSummary(): String {
    val on = AnswerProviders.enabledIds.size
    val total = AnswerProviders.all.size
    val units = when (GumshoosConfig.unitSystemOverride) {
        MeasurementSystem.SI -> "Metric"
        MeasurementSystem.US, MeasurementSystem.UK -> "Imperial"
        null -> "Device units"
    }
    val currency = GumshoosConfig.preferredCurrency ?: "device currency"
    return "$on of $total on · $units · $currency"
}

private fun launchingSummary(hasOverlay: Boolean, drawerIcon: Boolean): String = when {
    !hasOverlay -> "Permission needed"
    drawerIcon -> "Widget · second app icon on"
    else -> "Widget ${GumshoosConfig.widgetIconSizeDp}dp"
}

/** Counts what actually appears, so an uninstalled provider isn't reported as available. */
private fun providersSummary(): String {
    val shown = SearchProviders.enabled
    return when {
        shown.isEmpty() -> "None — the \"Search using\" row is hidden"
        else -> "${shown.size} shown · ${shown.first().label} first"
    }
}

/** Motion first: it's the setting here that changes how the whole app feels. */
private fun appearanceSummary(): String {
    val animations = when (GumshoosConfig.animationMode) {
        AnimationMode.FOLLOW_SYSTEM -> "Animations follow system"
        AnimationMode.ALWAYS_ON -> "Animations always on"
        AnimationMode.ALWAYS_OFF -> "Animations off"
    }
    return "$animations · widget ${GumshoosConfig.widgetIconSizeDp}dp"
}
