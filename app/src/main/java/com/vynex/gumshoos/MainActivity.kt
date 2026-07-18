package com.vynex.gumshoos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vynex.gumshoos.ui.theme.GumshoosTheme

/**
 * Setup surface only. This is deliberately NOT the fast path — tapping the launcher icon pays the
 * full activity-launch cost, which is exactly what the widget exists to avoid.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            GumshoosTheme {
                Scaffold { padding ->
                    SetupScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var strategy by remember { mutableStateOf(Prefs.prewarmStrategy(context)) }

    // Permission is granted in a different app, so re-check whenever we come back to the front.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Gumshoos", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Latency proof-of-concept. Place the widget on your home screen, tap it, and read the " +
                "number that appears under the search bar.",
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("1 · Permission", style = MaterialTheme.typography.titleMedium)
        Text(
            if (hasOverlay) "Overlay permission granted." else "Overlay permission required.",
            style = MaterialTheme.typography.bodySmall,
            color = if (hasOverlay) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
        )
        Button(
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${context.packageName}".toUri(),
                    )
                )
            },
            enabled = !hasOverlay,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grant overlay permission") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("2 · Keep it warm", style = MaterialTheme.typography.titleMedium)
        Text(
            "Starts the foreground service and pre-attaches the window. Needed once per boot; the " +
                "widget also does this when placed.",
            style = MaterialTheme.typography.bodySmall,
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

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("3 · Pre-warm strategy", style = MaterialTheme.typography.titleMedium)
        Text(
            "The variable under test. GONE skips measure/layout while hidden; PREDRAWN forces one " +
                "full frame at attach time. Switching restarts the service.",
            style = MaterialTheme.typography.bodySmall,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrewarmStrategy.entries.forEach { option ->
                FilterChip(
                    selected = strategy == option,
                    onClick = {
                        Prefs.setPrewarmStrategy(context, option)
                        strategy = option
                        Latency.reset()
                        // Restart so the new strategy applies at attach time.
                        context.stopService(Intent(context, OverlayService::class.java))
                        context.startForegroundService(
                            OverlayService.intent(context, OverlayService.ACTION_WARM)
                        )
                    },
                    label = { Text(option.name) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("4 · Compare", style = MaterialTheme.typography.titleMedium)
        Text(
            "This button is the slow path on purpose — it shows the overlay from an activity, so " +
                "you can feel the difference against the widget.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = {
                context.startForegroundService(
                    OverlayService.intent(context, OverlayService.ACTION_SHOW, source = "in-app")
                )
            },
            enabled = hasOverlay,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Show overlay from app") }

        OutlinedButton(
            onClick = { Latency.reset() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reset measurements") }
    }
}
