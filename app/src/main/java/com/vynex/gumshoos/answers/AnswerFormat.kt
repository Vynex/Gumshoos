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
}
