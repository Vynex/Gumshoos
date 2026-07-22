package com.vynex.gumshoos.widget

import androidx.annotation.DrawableRes
import com.vynex.gumshoos.LENS_PACKAGE
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
 *
 * [iconScale] is an **optical** correction, and only that.
 *
 * The three marks are drawn to the same box but do not read as the same size, because shape decides
 * apparent weight: a filled rounded square carries mass right out to its bounding box, while a
 * four-pointed star and a narrow mic concentrate theirs in the middle and read smaller at identical
 * dimensions. This is the same reason Material's own keylines size a square smaller than a circle.
 *
 * It is deliberately **not** the place to fix an artwork that carries its own margin — Lens used to,
 * a 12.5% transparent border from Google's export, and it was cropped out of
 * `design/icons/ic_provider_lens.svg` instead. Padding baked into a canvas is invisible from here
 * and indistinguishable from that defect the next time anyone measures; a number on this row is not.
 * Crop the artwork, then trim it here if the eye still asks for it.
 *
 * [kind] picks which of a slot's three views draws. Every slot in the layout holds an ImageView, a
 * TextClock and a TextView, and shows exactly one, so a text action is an ordinary action in every
 * other respect — it takes a position, it can be dragged to any of them, and it counts against
 * [SLOT_COUNT] like the rest. The trio exists precisely so this changes what a slot looks like
 * without changing where it can be.
 *
 * A text action's [icon] is never drawn on the bar, but it is not decoration: the settings row
 * carries it.
 *
 * [requiredPackage] is the app the action cannot work without. Null for the ones that go through
 * whatever the phone already has — voice search and the assistant both end on an unscoped intent, so
 * they land somewhere on any device. Lens does not: see [com.vynex.gumshoos.WidgetActionTargets].
 */
/**
 * Which of a slot's three views an action draws into.
 *
 * [CLOCK] is a `TextClock`, which re-evaluates a date pattern on its own ticker and so never goes
 * stale without help. [TEXT] is a plain `TextView` we push a string into — it cannot self-update,
 * which is why anything using it owns a staleness policy of its own (see [WeatherRepository]).
 * The distinction is not cosmetic: it is exactly the difference between a value the platform can
 * keep current and one we have to.
 */
enum class SlotKind { ICON, CLOCK, TEXT }

enum class WidgetAction(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
    val requestCode: Int,
    val tintable: Boolean,
    val description: String,
    /** Fraction of `widget_bar_action_size` this mark is drawn at. See the note above. */
    val iconScale: Float = 1f,
    val kind: SlotKind = SlotKind.ICON,
    /**
     * No tap target of its own, so a press lands on the bar and opens search.
     *
     * For a readout rather than a button: the temperature is information the bar is showing, and
     * making a strip of it launch something else would put a second meaning on the one gesture the
     * whole widget otherwise has. A slot that falls through needs no entry in
     * [com.vynex.gumshoos.WidgetActionTargets] at all.
     */
    val fallsThrough: Boolean = false,
    val requiredPackage: String? = null,
) {
    ASSISTANT(
        id = "assistant",
        label = "Gemini",
        icon = R.drawable.ic_provider_gemini,
        requestCode = 110,
        tintable = false,
        description = "Opens your phone's assistant app",
    ),
    VOICE(
        id = "voice",
        label = "Voice Search",
        icon = R.drawable.ic_provider_voice,
        requestCode = 111,
        // The supplied mark is Google's four-colour mic, not a mono glyph.
        tintable = false,
        description = "Opens Google's Voice Search screen",
    ),
    LENS(
        id = "lens",
        label = "Lens",
        icon = R.drawable.ic_provider_lens,
        requestCode = 112,
        tintable = false,
        description = "Opens Google Lens camera search",
        // ~2dp off a 21dp box. The camera outline is the squarest of the three and sat visibly
        // large beside them once its export margin was removed.
        iconScale = 0.905f,
        // The standalone app, and nothing else. Every entry point the Google app exposes was tested
        // one by one on a phone without it: all of them start, report success and draw nothing,
        // except a YouTube-specific activity that opens the wrong thing. So this action is only
        // honest where the app is present, and it hides itself where it is not.
        requiredPackage = LENS_PACKAGE,
    ),
    DATE(
        id = "date",
        label = "Date",
        icon = R.drawable.ic_provider_google_calendar,
        requestCode = 113,
        // Never reaches an ImageView, so there is nothing to tint or scale; the bar's TextClock
        // takes the content colour as text instead.
        tintable = false,
        description = "Shows today's date",
        kind = SlotKind.CLOCK,
    ),
    WEATHER(
        id = "weather",
        label = "Weather",
        icon = R.drawable.ic_provider_ms_weather,
        requestCode = 114,
        tintable = false,
        description = "Shows the current temperature",
        kind = SlotKind.TEXT,
        fallsThrough = true,
    ),
    ;

    val rendersAsText: Boolean get() = kind != SlotKind.ICON

    companion object {
        /**
         * How many actions the layout has room for.
         *
         * RemoteViews cannot add views, so the slots are fixed in XML, and each one is an ImageView
         * and a TextClock that take turns. A test asserts the enum never outgrows this — an action
         * past the last slot is dropped at render time with nothing to say so. It is deliberately
         * equal to the number of actions rather than one less: every action being placeable at once
         * is what makes the order in settings a real choice instead of a queue for scarce room.
         */
        const val SLOT_COUNT = 5

        /**
         * Shown out of the box.
         *
         * An id stored by an older build that no longer exists here — "lens" — simply doesn't
         * resolve and is dropped, so no migration is needed for anyone who had it switched on.
         */
        val DEFAULT_ENABLED: Set<String> = setOf(VOICE.id, LENS.id)

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
