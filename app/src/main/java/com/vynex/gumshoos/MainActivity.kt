package com.vynex.gumshoos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vynex.gumshoos.settings.SettingsHost
import com.vynex.gumshoos.ui.theme.GumshoosTheme

/**
 * Settings, and nothing else.
 *
 * Deliberately NOT the fast path to search — tapping the launcher icon pays the full activity
 * launch cost, which is exactly what the widget exists to avoid.
 *
 * POST_NOTIFICATIONS is never requested. A foreground service must supply a notification, but on
 * Android 13+ the system only displays it when that permission is granted, so leaving it denied is
 * the supported way to run the service without a permanent entry in the shade.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GumshoosTheme {
                SettingsHost()
            }
        }
    }
}
