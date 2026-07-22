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

    /**
     * Re-pushes the widgets every time settings comes to the front.
     *
     * A placed widget keeps whatever RemoteViews it was last handed, and part of what it draws now
     * depends on which apps are installed — an action whose app is gone must stop appearing, and one
     * whose app arrives must start. `OverlayService`'s package callback does that the instant it
     * happens, but only while the service is alive, and it is the first thing an aggressive OEM
     * reaps. That left the observed failure: install Lens, come back, and the bar still has no Lens
     * icon until some unrelated setting is toggled.
     *
     * So this is the backstop rather than the mechanism. Opening the app is exactly when someone is
     * looking to fix such a thing, and it costs what dragging a style slider one notch costs.
     */
    override fun onResume() {
        super.onResume()
        refreshAllWidgets(this)
        WeatherRepository.refreshIfDue(this)
    }

    private fun readRequest(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_MANAGE_APP_KEY) ?: return
        // Cleared so a later configuration-change recreate doesn't re-jump to the app.
        intent.removeExtra(EXTRA_MANAGE_APP_KEY)
        request = ManageAppsRequest(key, nonce++)
    }

    companion object {
        private const val EXTRA_MANAGE_APP_KEY = "manage_app_key"

        /**
         * Opens settings at its root. NEW_TASK because the callers are the overlay's window and
         * the service behind it, neither of which is an activity.
         */
        fun settingsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Opens settings on Manage apps, scrolled to and highlighting one launcher activity. */
        fun manageAppsIntent(context: Context, componentKey: String): Intent =
            settingsIntent(context).putExtra(EXTRA_MANAGE_APP_KEY, componentKey)
    }
}
