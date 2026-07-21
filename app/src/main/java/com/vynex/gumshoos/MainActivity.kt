package com.vynex.gumshoos

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vynex.gumshoos.settings.ManageAppsRequest
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

    // The search window's Edit action deep-links here. singleTask means an already-open settings
    // gets the intent through onNewIntent, so the request is state read from composition, not a
    // one-shot in onCreate. The nonce lets the same app be requested twice in a row and still fire.
    private var request by mutableStateOf<ManageAppsRequest?>(null)
    private var nonce = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readRequest(intent)
        enableEdgeToEdge()
        setContent {
            GumshoosTheme {
                SettingsHost(manageAppsRequest = request)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRequest(intent)
    }

    private fun readRequest(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_MANAGE_APP_KEY) ?: return
        // Cleared so a later configuration-change recreate doesn't re-jump to the app.
        intent.removeExtra(EXTRA_MANAGE_APP_KEY)
        request = ManageAppsRequest(key, nonce++)
    }

    companion object {
        private const val EXTRA_MANAGE_APP_KEY = "manage_app_key"

        /** Opens settings on Manage apps, scrolled to and highlighting one launcher activity. */
        fun manageAppsIntent(context: Context, componentKey: String): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_MANAGE_APP_KEY, componentKey)
    }
}
