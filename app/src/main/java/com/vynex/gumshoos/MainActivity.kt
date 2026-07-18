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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.sp
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

        CrashReport()

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

        Text("3 · Compare", style = MaterialTheme.typography.titleMedium)
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

/**
 * Surfaces the last uncaught exception, including ones thrown inside the overlay service where
 * there's no UI to show them. Copyable so the trace can be pasted somewhere useful.
 */
@Composable
private fun CrashReport() {
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
