package com.vynex.gumshoos

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tunables that will eventually be exposed in a settings screen. Persisted now so that screen only
 * has to render them, not migrate anything.
 */
object GumshoosConfig {
    private const val FILE = "gumshoos_config"
    private const val KEY_NEW_UPDATED_WINDOW_HOURS = "new_updated_window_hours"
    private const val KEY_WIDGET_ICON_SIZE = "widget_icon_size_dp"
    private const val KEY_WIDGET_ICON_OFFSET = "widget_icon_offset_dp"
    private const val KEY_CONTACT_ACTION_PACKAGE = "contact_action_package_"

    const val DEFAULT_NEW_UPDATED_WINDOW_HOURS = 12L

    /** Matches the measured diameter of a real launcher icon on the reference device. */
    const val DEFAULT_WIDGET_ICON_SIZE_DP = 46

    /**
     * Vertical nudge from the centre of the launcher cell, in dp. Positive moves it down.
     * Zero centres it, which is where most launchers draw an app icon; some sit slightly lower.
     */
    const val DEFAULT_WIDGET_ICON_OFFSET_DP = 0

    /** How recently an app must have been installed or updated to show under NEW/UPDATED. */
    var newUpdatedWindowHours by mutableStateOf(DEFAULT_NEW_UPDATED_WINDOW_HOURS)
        private set

    /** Drawn diameter of the 1x1 widget's icon, in dp. */
    var widgetIconSizeDp by mutableStateOf(DEFAULT_WIDGET_ICON_SIZE_DP)
        private set

    /** Vertical offset from the centre of the cell, in dp; positive is downward. */
    var widgetIconOffsetDp by mutableStateOf(DEFAULT_WIDGET_ICON_OFFSET_DP)
        private set

    /**
     * Chosen handler per contact action, or null to let the system decide. Keyed by action so the
     * dialer and SMS app can be exposed the same way later; only email is surfaced today, since
     * that's the one where a missing default produces the resolver's cogwheel.
     */
    var contactActionPackages by mutableStateOf<Map<ContactAction, String>>(emptyMap())
        private set

    fun setContactActionPackage(context: Context, action: ContactAction, packageName: String?) {
        contactActionPackages = contactActionPackages.toMutableMap().apply {
            if (packageName == null) remove(action) else put(action, packageName)
        }
        prefs(context).edit().apply {
            if (packageName == null) {
                remove(KEY_CONTACT_ACTION_PACKAGE + action.name)
            } else {
                putString(KEY_CONTACT_ACTION_PACKAGE + action.name, packageName)
            }
        }.apply()
    }

    fun load(context: Context) {
        val p = prefs(context)
        contactActionPackages = ContactAction.entries.mapNotNull { action ->
            p.getString(KEY_CONTACT_ACTION_PACKAGE + action.name, null)?.let { action to it }
        }.toMap()
        newUpdatedWindowHours =
            p.getLong(KEY_NEW_UPDATED_WINDOW_HOURS, DEFAULT_NEW_UPDATED_WINDOW_HOURS)
        widgetIconSizeDp = p.getInt(KEY_WIDGET_ICON_SIZE, DEFAULT_WIDGET_ICON_SIZE_DP)
        widgetIconOffsetDp = p.getInt(KEY_WIDGET_ICON_OFFSET, DEFAULT_WIDGET_ICON_OFFSET_DP)
    }

    fun setWidgetIconSizeDp(context: Context, dp: Int) {
        widgetIconSizeDp = dp
        prefs(context).edit().putInt(KEY_WIDGET_ICON_SIZE, dp).apply()
    }

    fun setWidgetIconOffsetDp(context: Context, dp: Int) {
        widgetIconOffsetDp = dp
        prefs(context).edit().putInt(KEY_WIDGET_ICON_OFFSET, dp).apply()
    }

    fun setNewUpdatedWindowHours(context: Context, hours: Long) {
        newUpdatedWindowHours = hours
        prefs(context).edit().putLong(KEY_NEW_UPDATED_WINDOW_HOURS, hours).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
