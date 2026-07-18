package com.vynex.gumshoos

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

@Composable
fun OverlayPermissionScreen() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant Overlay Permission")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (Settings.canDrawOverlays(context)) {
                    context.startService(Intent(context, OverlayService::class.java))
                }
                hasPermission = Settings.canDrawOverlays(context)
            },
            enabled = hasPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Launch Overlay")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val shortcutManager = context.getSystemService(ShortcutManager::class.java)

                val shortcut = ShortcutInfo.Builder(context, "overlay_shortcut_pin")
                    .setShortLabel("Search Overlay")
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher_foreground))
                    .setIntent(Intent(context, LaunchOverlayActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                    })
                    .build()

                if (shortcutManager.isRequestPinShortcutSupported) {
                    val pinnedShortcutCallbackIntent = shortcutManager.createShortcutResultIntent(shortcut)
                    val successCallback = PendingIntent.getBroadcast(
                        context, 0, pinnedShortcutCallbackIntent, PendingIntent.FLAG_IMMUTABLE
                    )
                    shortcutManager.requestPinShortcut(shortcut, successCallback.intentSender)
                }
            },
            enabled = hasPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Shortcut")
        }

        if (!hasPermission) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Overlay permission not granted", color = MaterialTheme.colorScheme.error)
        }
    }
}