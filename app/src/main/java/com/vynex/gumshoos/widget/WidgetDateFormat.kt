package com.vynex.gumshoos.widget

import android.text.format.DateFormat
import java.util.Locale

/**
 * The date pattern the bar's TextClock is given.
 *
 * A skeleton rather than a written-out pattern, because the order of day and month is the locale's
 * business and not ours: `getBestDateTimePattern` answers "d MMMM" for en-GB and "MMMM d" for en-US
 * off the same request. Writing either one out would be correct in one place and wrong in the other.
 *
 * No year, matching what a home screen wants — the bar has room for a date, not a datestamp.
 *
 * Not unit-tested, and it cannot be: `getBestDateTimePattern` is ICU-backed and
 * `android.text.format` is one of the stubbed packages on a JVM test, so a test here would assert
 * against `Stub!` rather than against a pattern. The thing worth checking is what a device shows,
 * which is a sideload.
 */
object WidgetDateFormat {

    /** Day and full month, in whatever order the locale puts them. */
    private const val SKELETON = "dMMMM"

    fun patternFor(locale: Locale): String = DateFormat.getBestDateTimePattern(locale, SKELETON)
}
