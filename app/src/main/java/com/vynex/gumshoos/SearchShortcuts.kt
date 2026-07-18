package com.vynex.gumshoos

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/**
 * The two ways to get a tappable icon that opens search, both routed through
 * [LaunchSearchActivity] because a shortcut cannot start a service directly.
 */
object SearchShortcuts {

    private const val ALIAS = "com.vynex.gumshoos.SearchShortcut"
    private const val PINNED_ID = "gumshoos_search"

    /** Whether the second app-drawer icon is showing. */
    fun isDrawerIconEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /**
     * Enabling the alias component is what makes the icon appear in the drawer; there is no other
     * way to add or remove a launcher entry at runtime.
     */
    fun setDrawerIconEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            // Without DONT_KILL_APP the change would take our own process down with it, including
            // the warm overlay window.
            PackageManager.DONT_KILL_APP,
        )
    }

    fun canPin(context: Context): Boolean =
        context.getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true

    /** Asks the launcher to place an icon on the home screen. The launcher shows the confirmation. */
    fun requestPin(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        if (!manager.isRequestPinShortcutSupported) return

        val shortcut = ShortcutInfo.Builder(context, PINNED_ID)
            .setShortLabel(context.getString(R.string.search_shortcut_label))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_widget_search_app))
            .setIntent(
                Intent(context, LaunchSearchActivity::class.java).setAction(Intent.ACTION_VIEW)
            )
            .build()

        runCatching { manager.requestPinShortcut(shortcut, null) }
    }

    private fun alias(context: Context) = ComponentName(context.packageName, ALIAS)
}
