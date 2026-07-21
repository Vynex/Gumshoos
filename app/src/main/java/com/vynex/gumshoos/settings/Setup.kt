package com.vynex.gumshoos.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vynex.gumshoos.AppPermission
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.OverlayService

/**
 * What is granted, and how to ask for the rest.
 *
 * The state and the asking are one object because two screens need both: first-run setup and the
 * Permissions screen in settings show the same list and take the same actions on it, and they used
 * to be the *same composable* — which is why tapping "Permissions" in settings arrived at a screen
 * headed "Set up Gumshoos" with a Done button on it.
 */
private class PermissionState(
    val granted: Set<AppPermission>,
    val request: (AppPermission) -> Unit,
)

@Composable
private fun rememberPermissionState(): PermissionState {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(AppPermission.entries.filter { it.isGranted(context) }.toSet())
    }

    // Two of the three are granted in another app entirely, so the only reliable moment to re-read
    // them is coming back to the front.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = AppPermission.entries.filter { it.isGranted(context) }.toSet()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestContacts = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed) {
            granted = granted + AppPermission.CONTACTS
            // Re-warm so contacts are indexed now rather than at the next boot.
            context.startForegroundService(
                OverlayService.intent(context, OverlayService.ACTION_WARM)
            )
        }
    }

    return PermissionState(granted) { permission ->
        when (permission) {
            AppPermission.OVERLAY -> context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri(),
                )
            )
            AppPermission.USAGE -> context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            )
            AppPermission.CONTACTS -> requestContacts.launch(Manifest.permission.READ_CONTACTS)
        }
    }
}

/**
 * The permission list, in the shared settings vocabulary.
 *
 * Dividered, because each row is a separate decision — the same call the root list and the Search
 * hub make. Required and Optional are two groups rather than two accent headings over loose cards;
 * a permission was a `Card` of its own before, which made a screen of three permissions a screen of
 * three competing surfaces.
 */
@Composable
private fun PermissionGroups(state: PermissionState) {
    SettingsGroup(title = "Required") {
        AppPermission.required.forEachIndexed { index, permission ->
            if (index > 0) SettingsGroupDivider()
            PermissionRow(permission, permission in state.granted) { state.request(permission) }
        }
    }
    SettingsGroup(title = "Optional") {
        AppPermission.optional.forEachIndexed { index, permission ->
            if (index > 0) SettingsGroupDivider()
            PermissionRow(permission, permission in state.granted) { state.request(permission) }
        }
    }
}

/**
 * One permission: what it is for, and what is lost without it.
 *
 * `withoutIt` shows only while the permission is missing. Once granted, what would have happened is
 * no longer a thing the reader has to weigh, and leaving it there reads as a warning about a state
 * they are not in.
 *
 * The row is inert once granted — no chevron, no ripple. Revoking happens in the system's own
 * screens, and the runtime one cannot be re-asked at all, so a tap here would either do nothing or
 * silently reopen a screen the user did not ask for.
 */
@Composable
private fun PermissionRow(
    permission: AppPermission,
    isGranted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(enabled = !isGranted, onClick = onGrant)
            .fillMaxWidth()
            .padding(horizontal = SettingsInset, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(permission.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                permission.why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isGranted && permission.withoutIt.isNotEmpty()) {
                Text(
                    "Without it: ${permission.withoutIt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (isGranted) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Granted",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The Permissions screen in settings.
 *
 * A subscreen like any other: the shared bar, a back arrow, and no way to "finish" — there is
 * nothing to finish, since it is a list of states rather than a sequence of steps.
 */
@Composable
internal fun PermissionsScreen(onBack: () -> Unit) {
    val state = rememberPermissionState()

    SettingsScaffold(title = "Permissions", onBack = onBack) {
        SettingsNote(
            "Search needs one permission to open at all. The other two add to what it can find, " +
                "and can be left off.",
        )
        PermissionGroups(state)
    }
}

/**
 * First-run setup, shown ahead of settings.
 *
 * Deliberately **not** a settings subscreen, which is why it keeps its own heading and no app bar:
 * it is a sequence with an end, reached before there is anything to go back to. Every permission is
 * explained before it is requested, including the optional ones — a system permission dialog says
 * what is being asked for but never why, and two of these three are granted in a Settings screen
 * elsewhere in the OS with no room to explain anything at all.
 *
 * Reached whenever a required permission is missing, so it also serves as the recovery path if the
 * overlay grant is revoked later — at which point search has silently stopped working, and this is
 * the screen that says so.
 */
@Composable
internal fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val state = rememberPermissionState()
    val missingRequired = AppPermission.required.any { it !in state.granted }

    /**
     * Both exits record that setup has been seen — the explanations are worth reading once, not on
     * every launch. The required-permission gate is independent of the flag, so skipping without
     * granting still brings this screen back next time.
     */
    fun dismiss() {
        GumshoosConfig.setSetupComplete(context)
        onDone()
    }

    fun finish() {
        // Granting the overlay permission doesn't start anything. Warming here is what actually
        // makes the first search fast, rather than leaving it to the next reboot.
        context.startForegroundService(
            OverlayService.intent(context, OverlayService.ACTION_WARM)
        )
        dismiss()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsInset),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Set up Gumshoos", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            SettingsNote(
                "Search from anywhere, on any launcher. Two of these are optional — what each " +
                    "one is for is below, so nothing is granted on trust.",
            )

            PermissionGroups(state)

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = ::finish,
                enabled = !missingRequired,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (missingRequired) "Grant the required permission" else "Done") }

            // Not a trap. Someone who wants to read the diagnostics, or decide later, can still get
            // in — settings keeps warning that search can't open until the grant is made.
            if (missingRequired) {
                TextButton(onClick = ::dismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip for now")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
