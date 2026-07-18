package com.vynex.gumshoos.answers

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What time it is somewhere else.
 *
 * The tz database ships with the OS, so this needs no network. All of the difficulty is in
 * [ZoneAliases] turning a typed name into a zone; here it is arithmetic and presentation.
 *
 * The resolved zone id is always shown, because abbreviations are ambiguous enough that a silent
 * wrong guess would be worse than no answer.
 */
object TimeAnswerProvider : AnswerProvider {

    override val id = "time"
    override val label = "Time"

    private const val TIME = """(\d{1,2}(?::\d{2})?\s*(?:am|pm|AM|PM)?)"""
    private const val PLACE = """([a-zA-Z][a-zA-Z /_]*)"""

    /** "time in tokyo", "now in utc", "time at london". */
    private val CURRENT = Regex("""^\s*(?:time|now)\s+(?:in|at)\s+$PLACE\s*$""", RegexOption.IGNORE_CASE)

    /** "tokyo time". */
    private val PLACE_TIME = Regex("""^\s*$PLACE\s+time\s*$""", RegexOption.IGNORE_CASE)

    /** "3pm IST to PST" — an explicit source zone. */
    private val BETWEEN = Regex(
        """^\s*$TIME\s+$PLACE\s+to\s+$PLACE\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** "3pm in london" — source is the device. */
    private val AT_PLACE = Regex(
        """^\s*$TIME\s+(?:in|at)\s+$PLACE\s*$""",
        RegexOption.IGNORE_CASE,
    )

    override fun answer(query: String, context: AnswerContext): Answer? {
        val deviceZone = runCatching { ZoneId.of(context.zoneId) }.getOrNull() ?: return null
        val now = ZonedDateTime.now(context.clock.withZone(deviceZone))

        CURRENT.matchEntire(query)?.let { match ->
            val target = ZoneAliases.resolve(match.groupValues[1], context) ?: return null
            return present(now, target, context, sourceLabel = null)
        }

        PLACE_TIME.matchEntire(query)?.let { match ->
            // Guarded harder than the others: this pattern is two bare words, so without requiring
            // a resolvable place it would claim things like "screen time".
            val target = ZoneAliases.resolve(match.groupValues[1], context) ?: return null
            return present(now, target, context, sourceLabel = null)
        }

        BETWEEN.matchEntire(query)?.let { match ->
            val source = ZoneAliases.resolve(match.groupValues[2], context) ?: return null
            val target = ZoneAliases.resolve(match.groupValues[3], context) ?: return null
            val time = parseTime(match.groupValues[1]) ?: return null
            val moment = ZonedDateTime.of(LocalDate.now(context.clock.withZone(source)), time, source)
            return present(moment, target, context, sourceLabel = describe(moment, source, context))
        }

        AT_PLACE.matchEntire(query)?.let { match ->
            val target = ZoneAliases.resolve(match.groupValues[2], context) ?: return null
            val time = parseTime(match.groupValues[1]) ?: return null
            val moment = ZonedDateTime.of(now.toLocalDate(), time, deviceZone)
            return present(moment, target, context, sourceLabel = describe(moment, deviceZone, context))
        }

        return null
    }

    private fun present(
        moment: ZonedDateTime,
        target: ZoneId,
        context: AnswerContext,
        sourceLabel: String?,
    ): Answer {
        val converted = moment.withZoneSameInstant(target)
        val timeText = timeFormatter(context).format(converted)

        // The date only appears when it differs, so the common case stays uncluttered but crossing
        // midnight is never hidden.
        val crossesDate = converted.toLocalDate() != moment.toLocalDate()
        val headline = if (crossesDate) {
            "$timeText, ${dateFormatter(context).format(converted)}"
        } else {
            timeText
        }

        val zoneName = target.id.replace('_', ' ')
        val detail = if (sourceLabel != null) "$sourceLabel  →  $zoneName" else zoneName

        return Answer(
            providerId = id,
            headline = headline,
            detail = detail,
            copyText = headline,
        )
    }

    private fun describe(moment: ZonedDateTime, zone: ZoneId, context: AnswerContext): String =
        "${timeFormatter(context).format(moment)} ${zone.id.replace('_', ' ')}"

    private fun timeFormatter(context: AnswerContext): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(context.locale)

    private fun dateFormatter(context: AnswerContext): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(context.locale)

    /** "3", "3pm", "15:30", "3:30 pm". */
    private fun parseTime(raw: String): LocalTime? {
        val text = raw.trim().lowercase().replace(" ", "")
        val isPm = text.endsWith("pm")
        val isAm = text.endsWith("am")
        val digits = text.removeSuffix("pm").removeSuffix("am")

        val parts = digits.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (minute !in 0..59) return null

        val hour24 = when {
            isPm && hour < 12 -> hour + 12
            isAm && hour == 12 -> 0
            // Without am/pm a bare hour is read as given, so "15" is afternoon and "3" is morning.
            else -> hour
        }
        if (hour24 !in 0..23) return null
        return LocalTime.of(hour24, minute)
    }
}
