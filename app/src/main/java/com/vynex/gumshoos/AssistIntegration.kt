package com.vynex.gumshoos

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Offering the search window as the phone's assistant.
 *
 * Two separate things have to be true, and keeping them apart is the whole of this file:
 *
 *  1. **We advertise an assistant entry point.** An app becomes eligible for the system's assistant
 *     picker by declaring an activity that handles `ACTION_ASSIST`. That is [AssistTrigger], an
 *     alias onto [LaunchSearchActivity] — the same trampoline the shortcut uses, since assist also
 *     arrives as an activity launch and cannot reach a service directly.
 *  2. **The user picks us in system settings.** Nothing here can do that. The assistant is a
 *     protected role; there is no API for an app to award it to itself, and there is no request
 *     dialog for it the way there is for a default browser or dialler. [openPicker] can only walk
 *     the user to the screen.
 *
 * The alias is disabled by default and toggled by [setEntryEnabled], the same mechanism as the
 * second drawer icon. A permanently-declared filter would sit in every user's assistant picker
 * whether or not they ever wanted it there, and "the option is off" would mean nothing.
 */
object AssistIntegration {

    private const val ALIAS = "com.vynex.gumshoos.AssistTrigger"

    /** Whether we are offering ourselves to the picker at all. */
    fun isEntryEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /**
     * Enabling the alias is what puts us in the system's list; there is no runtime API for it.
     *
     * DONT_KILL_APP for the reason it matters everywhere else in this app: without it the change
     * takes our own process down, and with it the warm overlay window.
     */
    fun setEntryEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
        EventLog.record("assist entry ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Whether the system currently routes assist to us.
     *
     * [RoleManager.isRoleHeld] is the public, permission-free answer to exactly this question, and
     * is preferred over reading the `assistant` secure setting — that key is not public API, and a
     * string compare against a flattened component name is one OEM away from being wrong.
     *
     * Null means the question couldn't be answered rather than "no". The distinction is the point:
     * this app has been bitten repeatedly by a probe that reports a confident negative for
     * something sitting right there, so the UI says "couldn't tell" instead of guessing.
     */
    fun isCurrentAssistant(context: Context): Boolean? = runCatching {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }.getOrNull()

    /**
     * Opens the system screen where the assistant is chosen.
     *
     * A chain rather than one intent, resolved before starting, with the rung that won recorded —
     * the house style, and warranted here: the assistant picker has moved between Settings screens
     * across versions and OEM skins, and a refused or unresolvable intent looks exactly like a
     * working one from this side.
     *
     * Returns false if nothing resolved, so the caller can say so rather than appear to have done
     * something.
     */
    fun openPicker(context: Context): Boolean {
        val chain = listOf(
            // The assistant and voice input screen: where the picker actually lives on AOSP.
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            // Default apps, which contains the assistant entry on skins that reorganised it.
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )

        chain.forEachIndexed { index, intent ->
            val target = runCatching {
                context.packageManager.resolveActivity(intent, 0)
            }.getOrNull() ?: return@forEachIndexed

            val started = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (started) {
                EventLog.record(
                    "assist picker -> rung${index + 1} ${target.activityInfo?.name}"
                )
                return true
            }
            EventLog.record("assist picker rung${index + 1} refused")
        }
        EventLog.record("assist picker -> nothing resolved")
        return false
    }

    private fun alias(context: Context) = ComponentName(context.packageName, ALIAS)
}
