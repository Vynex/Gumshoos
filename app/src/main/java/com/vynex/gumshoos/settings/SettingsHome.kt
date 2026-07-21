package com.vynex.gumshoos.settings

import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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
import com.vynex.gumshoos.AppPermission
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.R
import com.vynex.gumshoos.SearchProviders
import com.vynex.gumshoos.UsageRepository
import com.vynex.gumshoos.UsageSource
import com.vynex.gumshoos.answers.AnswerProviders

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

    // Permissions are granted in other apps, so re-read whenever we come back to the front.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                hasUsage = UsageRepository.hasPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsRootScaffold(title = "Gumshoos") {
        if (!hasOverlay) {
            SettingsWarning(
                "Search can't open without permission to draw over other apps. " +
                    "Grant it under Permissions.",
            )
            Spacer(Modifier.height(8.dp))
        }

        SettingsGroupCard {
            CategoryRow(
                title = "Permissions",
                summary = permissionsSummary(),
                onClick = { onOpen(Screen.Permissions) },
                leadingIcon = R.drawable.ic_provider_terminal,
                fullBleed = false,
            )
            SettingsGroupDivider()
            CategoryRow(
                title = "Appearance",
                summary = appearanceSummary(),
                onClick = { onOpen(Screen.Appearance) },
                leadingIcon = R.drawable.ic_provider_moon,
                fullBleed = false,
            )
            SettingsGroupDivider()
            CategoryRow(
                title = "Manage apps",
                summary = manageAppsSummary(),
                onClick = { onOpen(Screen.ManageApps()) },
                leadingIcon = R.drawable.ic_provider_text_search,
                fullBleed = false,
            )
            SettingsGroupDivider()
            CategoryRow(
                title = "Search",
                summary = searchSummary(),
                onClick = { onOpen(Screen.Search) },
                leadingIcon = R.drawable.ic_provider_search_code,
                fullBleed = false,
            )
            SettingsGroupDivider()
            CategoryRow(
                title = "Behaviour",
                summary = behaviourSummary(hasUsage),
                onClick = { onOpen(Screen.Behaviour) },
                leadingIcon = R.drawable.ic_provider_wrench,
                fullBleed = false,
            )
        }

        // The list's own arrangement adds 4dp either side, so this is a 12dp gap — enough to read
        // as a separate group without the two looking unrelated.
        Spacer(Modifier.height(4.dp))

        SettingsGroupCard {
            CategoryRow(
                title = "Advanced",
                summary = "Auto-close, launching, diagnostics",
                onClick = { onOpen(Screen.Advanced) },
                leadingIcon = R.drawable.ic_provider_hat_glasses,
                fullBleed = false,
            )
        }
    }
}

/** Renamed and hidden counts, the two things this screen changes, on one line. */
private fun manageAppsSummary(): String {
    val renamed = GumshoosConfig.appAliases.size
    val hidden = GumshoosConfig.hiddenApps.size
    val parts = buildList {
        if (renamed > 0) add("$renamed renamed")
        if (hidden > 0) add("$hidden hidden")
    }
    return if (parts.isEmpty()) "Rename or hide any app" else parts.joinToString(" · ")
}

/** Ordering and the new-apps window, the two levers this screen now carries. */
private fun behaviourSummary(hasUsage: Boolean): String {
    val ordering = when {
        GumshoosConfig.usageSource == UsageSource.GUMSHOOS -> "Ordered by launches"
        hasUsage -> "Ordered by system usage"
        else -> "Alphabetical"
    }
    return "$ordering · new apps within ${GumshoosConfig.newUpdatedWindowHours}h"
}

/** Answers and providers carry live counts; matching and contacts sit behind the same row. */
private fun searchSummary(): String {
    val answersOn = AnswerProviders.enabledIds.size
    val answersTotal = AnswerProviders.all.size
    val providers = SearchProviders.enabled.size
    return "$answersOn of $answersTotal answers · $providers providers"
}

/** Leads with the required one, since that's the only state that stops search working. */
@Composable
private fun permissionsSummary(): String {
    val context = LocalContext.current
    val missingRequired = AppPermission.required.filterNot { it.isGranted(context) }
    val optionalOn = AppPermission.optional.count { it.isGranted(context) }
    return when {
        missingRequired.isNotEmpty() -> "${missingRequired.first().title} not granted"
        else -> "Required granted · $optionalOn of ${AppPermission.optional.size} optional"
    }
}

/** Motion first: it's the setting here that changes how the whole app feels. */
private fun appearanceSummary(): String {
    val animations = when (GumshoosConfig.animationMode) {
        AnimationMode.FOLLOW_SYSTEM -> "Animations follow system"
        AnimationMode.ALWAYS_OFF -> "Animations off"
    }
    return "$animations · widget ${GumshoosConfig.widgetIconSizeDp}dp"
}
