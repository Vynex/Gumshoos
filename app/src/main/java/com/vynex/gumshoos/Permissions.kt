package com.vynex.gumshoos

import android.content.Context
import android.provider.Settings

/**
 * Every permission the app asks for, and why.
 *
 * One list rather than an explanation per settings screen, because onboarding and settings have to
 * agree: a reason written in two places drifts, and this is the text that decides whether someone
 * grants a sensitive permission or uninstalls.
 *
 * POST_NOTIFICATIONS is deliberately absent. A foreground service must supply a notification, but
 * Android 13+ only displays it when that permission is granted — so leaving it denied is the
 * supported way to run the service without a permanent entry in the shade. Asking for it would make
 * the app worse.
 */
enum class AppPermission(
    val title: String,
    /** What it buys, in the user's terms. */
    val why: String,
    /** What is lost without it. Blank for the required one, which loses everything. */
    val withoutIt: String,
    /** Whether search can work at all without it. */
    val required: Boolean,
) {
    OVERLAY(
        title = "Draw over other apps",
        why = "The search window is drawn on top of whatever is already on screen. This is what " +
            "allows the app to be so fast.",
        withoutIt = "",
        required = true,
    ),
    USAGE(
        title = "Usage access",
        why = "Orders the Frequent and Recent tabs by the apps you actually open, including ones " +
            "you used before installing Gumshoos.",
        withoutIt = "",
        required = false,
    ),
    CONTACTS(
        title = "Contacts",
        why = "Lets you search through your phone's contacts as well.",
        withoutIt = "",
        required = false,
    ),
    ;

    fun isGranted(context: Context): Boolean = when (this) {
        OVERLAY -> Settings.canDrawOverlays(context)
        USAGE -> UsageRepository.hasPermission(context)
        CONTACTS -> ContactsRepository.hasPermission(context)
    }

    companion object {
        /**
         * Whether setup needs to be shown.
         *
         * Two separate reasons, and both matter. A missing required permission means search cannot
         * open at all, so this reappears if the grant is later revoked. Never having finished setup
         * means the optional permissions have never been explained — without that, someone whose
         * required permission happened to be granted already would never learn what contacts
         * access is for, only that it was refused.
         */
        fun shouldShowSetup(missingRequired: Boolean, setupComplete: Boolean): Boolean =
            missingRequired || !setupComplete

        val required: List<AppPermission> get() = entries.filter { it.required }
        val optional: List<AppPermission> get() = entries.filterNot { it.required }
    }
}
