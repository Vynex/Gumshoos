package com.vynex.gumshoos.answers

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.absoluteValue

/** Number formatting shared by every answer provider, so results read consistently. */
object AnswerFormat {

    /**
     * Rounds to 12 significant figures before formatting. That is well inside a double's ~15-17,
     * so it removes the artefacts that make a result look broken — 0.1 + 0.2 reading as
     * 0.30000000000000004 — without touching precision anyone asked for.
     */
    fun number(value: Double, locale: Locale): String {
        if (!value.isFinite()) return value.toString()

        val magnitude = value.absoluteValue
        if (magnitude != 0.0 && (magnitude >= 1e12 || magnitude < 1e-6)) {
            return DecimalFormat("0.######E0", DecimalFormatSymbols(locale)).format(value)
        }

        val rounded = BigDecimal(value)
            .round(MathContext(12))
            .setScale(10, RoundingMode.HALF_UP)
            .stripTrailingZeros()

        return DecimalFormat("#,##0.##########", DecimalFormatSymbols(locale)).format(rounded)
    }

    /**
     * An exchange rate, to four decimal places.
     *
     * Full precision reads as noise next to the converted amount — nobody needs ten decimals of
     * "1 USD = 83.2016549..." to sanity-check a conversion.
     *
     * The exception is a rate small enough that four decimals would round it to zero, which says
     * nothing at all. Those fall back to four significant figures, so 1 IDR = 0.0000059 USD still
     * carries information.
     */
    fun rate(value: Double, locale: Locale): String {
        if (!value.isFinite()) return value.toString()
        val symbols = DecimalFormatSymbols(locale)

        if (value != 0.0 && value.absoluteValue < 0.0001) {
            val significant = BigDecimal(value).round(MathContext(4)).stripTrailingZeros()
            return DecimalFormat("#,##0.##########", symbols).format(significant)
        }
        return DecimalFormat("#,##0.####", symbols).format(value)
    }

    /**
     * How long ago something happened, in words.
     *
     * A bare date makes the reader do the arithmetic: "18 Jul" is only meaningful if you know
     * today's date and think about it. "3 hours ago" answers the actual question, which is whether
     * the number can be trusted.
     */
    fun relativeTime(fromMillis: Long, nowMillis: Long): String {
        val elapsed = nowMillis - fromMillis
        // Clock skew, or rates published slightly ahead: not worth a "in 2 minutes" reading.
        if (elapsed < 0) return "just now"

        val minutes = elapsed / 60_000
        val hours = elapsed / 3_600_000
        val days = elapsed / 86_400_000

        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes ${plural(minutes, "minute")} ago"
            hours < 24 -> "$hours ${plural(hours, "hour")} ago"
            days < 30 -> "$days ${plural(days, "day")} ago"
            else -> (days / 30).let { "$it ${plural(it, "month")} ago" }
        }
    }

    private fun plural(count: Long, noun: String) = if (count == 1L) noun else "${noun}s"
}
