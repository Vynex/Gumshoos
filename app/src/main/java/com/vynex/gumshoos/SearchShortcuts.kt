package com.vynex.gumshoos

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/**
 * The two ways to get a tappable icon that opens search, both routed through
 * [LaunchSearchActivity] because a shortcut cannot start a service directly — plus the requests that
 * ask the launcher to place one of those, or a widget, on the home screen.
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

    /**
     * Asks the launcher to place an icon on the home screen. The launcher shows the confirmation.
     *
     * Returns whether the request was *accepted*, which is as far as we can see: the launcher may
     * still drop it, and where it lands is entirely its decision. Reported rather than swallowed
     * because a refusal and a success both look like nothing happening from this screen — the icon
     * may simply have gone onto a page the user isn't looking at.
     */
    fun requestPin(context: Context): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false

        val shortcut = ShortcutInfo.Builder(context, PINNED_ID)
            .setShortLabel(context.getString(R.string.search_shortcut_label))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_widget_search_app))
            .setIntent(
                Intent(context, LaunchSearchActivity::class.java).setAction(Intent.ACTION_VIEW)
            )
            .build()

        val accepted = runCatching { manager.requestPinShortcut(shortcut, null) }
            .getOrDefault(false)
        EventLog.record("pin search icon ${if (accepted) "accepted" else "REFUSED"}")
        return accepted
    }

    /**
     * Whether the launcher accepts widget-pinning requests at all.
     *
     * Separate from [canPin] because they are separate capabilities and a launcher can support one
     * without the other; asking one question for both would grey out a control that works.
     */
    fun canPinWidget(context: Context): Boolean =
        AppWidgetManager.getInstance(context)?.isRequestPinAppWidgetSupported == true

    /** Asks the launcher to place one of the two widgets. As [requestPin], the launcher decides. */
    fun requestPinWidget(context: Context, provider: Class<out AppWidgetProvider>): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        if (!manager.isRequestPinAppWidgetSupported) return false

        val accepted = runCatching {
            manager.requestPinAppWidget(ComponentName(context, provider), null, null)
        }.getOrDefault(false)
        EventLog.record(
            "pin widget ${provider.simpleName} ${if (accepted) "accepted" else "REFUSED"}"
        )
        return accepted
    }

    private fun alias(context: Context) = ComponentName(context.packageName, ALIAS)
}
