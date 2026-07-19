package com.vynex.gumshoos.widget

import androidx.annotation.DrawableRes
import com.vynex.gumshoos.R

/**
 * The tappable icons at the right of the search bar.
 *
 * [requestCode] is written out per entry rather than derived from `ordinal`. PendingIntent equality
 * ignores extras, so these codes are what keep three actions from overwriting each other — and an
 * ordinal would silently reshuffle live PendingIntents the day someone reorders this enum. 100 and
 * 101 belong to the two widget providers; this block starts clear of them.
 *
 * [tintable] marks the icons that follow the bar's contrast colour. The brand marks must not: a
 * tinted Gemini or Lens mark is flattened to one tone.
 */
enum class WidgetAction(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
    val requestCode: Int,
    val tintable: Boolean,
    val description: String,
) {
    ASSISTANT(
        id = "assistant",
        label = "Gemini",
        icon = R.drawable.ic_provider_gemini,
        requestCode = 110,
        tintable = false,
        description = "Opens the assistant your phone is set to use",
    ),
    VOICE(
        id = "voice",
        label = "Voice Search",
        icon = R.drawable.ic_provider_voice,
        requestCode = 111,
        // The supplied mark is Google's four-colour mic, not a mono glyph.
        tintable = false,
        description = "Speak a query, get Google results",
    ),
    ;

    // Lens is deliberately absent. Google publishes no intent for it, and every component name
    // launchers are known to use either failed to resolve or started something that closed itself
    // immediately. Request code 112 stays reserved rather than reused, so a stale PendingIntent
    // from a build that had Lens can't be handed to whatever takes that slot next.

    companion object {
        /**
         * How many action icons the layout has room for.
         *
         * RemoteViews cannot add views, so the slots are fixed in XML. A test asserts the enum never
         * outgrows this — otherwise a fourth action would be silently dropped at render time.
         */
        const val SLOT_COUNT = 3

        /**
         * Shown out of the box.
         *
         * An id stored by an older build that no longer exists here — "lens" — simply doesn't
         * resolve and is dropped, so no migration is needed for anyone who had it switched on.
         */
        val DEFAULT_ENABLED: Set<String> = setOf(VOICE.id)

        fun fromId(id: String?): WidgetAction? = entries.firstOrNull { it.id == id }

        /**
         * [enabled] padded with nulls to exactly [SLOT_COUNT].
         *
         * Lets `decorate` walk slots and actions together, so hiding an unused slot isn't a separate
         * code path that can be forgotten. Anything past the last slot is dropped here rather than
         * failing — the test is what keeps that from happening unnoticed.
         */
        fun slots(enabled: List<WidgetAction>): List<WidgetAction?> =
            List(SLOT_COUNT) { index -> enabled.getOrNull(index) }
    }
}
