package com.vynex.gumshoos.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.vynex.gumshoos.AppPermission
import com.vynex.gumshoos.GumshoosConfig
import com.vynex.gumshoos.OverlayService

/**
 * First-run setup, shown ahead of settings.
 *
 * Every permission is explained before it is requested, including the optional ones. A system
 * permission dialog says what is being asked for but never why, and two of these three are granted
 * in a Settings screen elsewhere in the OS with no room to explain anything at all.
 *
 * Reached whenever a required permission is missing, so it also serves as the recovery path if the
 * overlay grant is revoked later — at which point search has silently stopped working, and this is
 * the screen that says so.
 */
@Composable
internal fun SetupScreen(onDone: () -> Unit) {
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

    fun request(permission: AppPermission) = when (permission) {
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

    val missingRequired = AppPermission.required.any { it !in granted }

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

            SettingsSection("Required")
            AppPermission.required.forEach { permission ->
                PermissionCard(
                    permission = permission,
                    isGranted = permission in granted,
                    onGrant = { request(permission) },
                )
            }

            SettingsSection("Optional")
            AppPermission.optional.forEach { permission ->
                PermissionCard(
                    permission = permission,
                    isGranted = permission in granted,
                    onGrant = { request(permission) },
                )
            }

            Spacer(Modifier.height(20.dp))
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

@Composable
private fun PermissionCard(
    permission: AppPermission,
    isGranted: Boolean,
    onGrant: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    permission.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (isGranted) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Granted",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Granted", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                permission.why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (permission.withoutIt.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Without it: ${permission.withoutIt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isGranted) {
                Spacer(Modifier.height(12.dp))
                if (permission.required) {
                    Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant ${permission.title.lowercase()}")
                    }
                } else {
                    OutlinedButton(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow ${permission.title.lowercase()}")
                    }
                }
            }
        }
    }
}
