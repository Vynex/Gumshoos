package com.vynex.gumshoos.answers

import kotlin.math.abs
import kotlin.math.floor

/**
 * Unit conversion, with a default target chosen from the device's locale.
 *
 * The default is the point of the module. An Indian device typing `5ft` wants metres without
 * having to say so; `5ft to inches` always wins when it is said. See [defaultTarget].
 */
object UnitAnswerProvider : AnswerProvider {

    override val id = "units"
    override val label = "Conversion"

    /**
     * `<number> <unit> [to|in|as|> <unit>]`
     *
     * A unit token starts with a letter but may contain digits after it, so "m2" and "ft2" parse
     * while "5 20" still cannot be read as a unit.
     *
     * The separator list includes "in", which is also the symbol for inches. That resolves
     * naturally: "12 in" has nothing after it so "in" is the unit, while "5 ft in m" has a unit on
     * both sides so it is the preposition.
     */
    private val PATTERN = Regex(
        """^\s*(-?[\d.,]+)\s*([a-zA-Z°µ"'²³^/][a-zA-Z0-9°µ"'²³^/]*)""" +
            """(?:\s*(?:to|in|as|>|->)\s*([a-zA-Z°µ"'²³^/][a-zA-Z0-9°µ"'²³^/]*))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Feet and inches together: 5'10", 5' 10". */
    private val FEET_INCHES = Regex("""^\s*(\d+)\s*'\s*(\d+(?:\.\d+)?)\s*"?\s*$""")

    override fun answer(query: String, context: AnswerContext): Answer? {
        feetAndInches(query, context)?.let { return it }

        val match = PATTERN.matchEntire(query) ?: return null
        val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val from = UnitTables.find(match.groupValues[2]) ?: return null

        val explicitTarget = match.groupValues[3].takeIf { it.isNotEmpty() }
        val to = if (explicitTarget != null) {
            // An explicit target that doesn't exist, or crosses dimensions, is a failed conversion
            // rather than something to guess at — better to show nothing than "5 ft = 5 kg".
            UnitTables.find(explicitTarget)?.takeIf { it.dimension == from.dimension } ?: return null
        } else {
            defaultTarget(from, value, context)
        } ?: return null

        return build(value, from, to, context)
    }

    private fun feetAndInches(query: String, context: AnswerContext): Answer? {
        val match = FEET_INCHES.matchEntire(query) ?: return null
        val feet = match.groupValues[1].toDoubleOrNull() ?: return null
        val inches = match.groupValues[2].toDoubleOrNull() ?: return null

        val foot = UnitTables.find("ft") ?: return null
        val inch = UnitTables.find("in") ?: return null
        val totalMetres = foot.toBase(feet) + inch.toBase(inches)

        val target = UnitTables.preferredFor(Dimension.LENGTH, MeasurementSystem.SI)
            .let { pickByMagnitude(it, totalMetres) } ?: return null

        val converted = target.fromBase(totalMetres)
        val headline = "${AnswerFormat.number(converted, context.locale)} ${target.symbol}"
        return Answer(
            providerId = id,
            headline = headline,
            detail = "${match.groupValues[1]}' ${match.groupValues[2]}\" =",
            copyText = headline,
        )
    }

    private fun build(
        value: Double,
        from: UnitDef,
        to: UnitDef,
        context: AnswerContext,
    ): Answer {
        val base = from.toBase(value)
        val headline = if (to.dimension == Dimension.DURATION) {
            composeDuration(base, to, context)
        } else {
            "${AnswerFormat.number(to.fromBase(base), context.locale)} ${to.symbol}"
        }
        val source = "${AnswerFormat.number(value, context.locale)} ${from.symbol}"

        return Answer(
            providerId = id,
            headline = headline,
            detail = "$source =",
            copyText = headline,
        )
    }

    /**
     * Where a conversion lands when the query didn't say.
     *
     * 1. Input in a system the locale doesn't use → convert into the locale's system.
     *    An Indian device (SI) given `5ft` answers in metres.
     * 2. Input already in the locale's system → convert to the other system, since converting
     *    metres to metres tells nobody anything.
     * 3. Temperature has no system, so it pairs °C and °F, choosing by locale.
     * 4. Data and duration have no counterpart system either. They scale up when the number
     *    warrants it (1500 MB → 1.5 GB), and otherwise step to the neighbouring unit downward
     *    (1 min → 60 s, 2 h → 120 min).
     */
    private fun defaultTarget(from: UnitDef, value: Double, context: AnswerContext): UnitDef? {
        val base = from.toBase(value)

        when (from.dimension) {
            Dimension.TEMPERATURE -> {
                val celsius = UnitTables.find("c")
                val fahrenheit = UnitTables.find("f")
                return when (from.id) {
                    "c" -> fahrenheit
                    "f" -> celsius
                    else -> if (context.measurementSystem == MeasurementSystem.US) {
                        fahrenheit
                    } else {
                        celsius
                    }
                }
            }

            Dimension.DATA, Dimension.DURATION -> {
                val ladder = UnitTables.defaultLadderFor(from.dimension)
                // Scaling up when the number warrants it: 1500 MB reads better as 1.5 GB.
                pickByMagnitude(ladder, base)?.takeIf { it.id != from.id }?.let { return it }
                // Otherwise the neighbouring unit, downward. "1 min" means 60 s, not 60,000 ms —
                // reaching for the smallest unit in the ladder is never what was wanted.
                return stepDown(from, ladder)
            }

            else -> {
                val localeSystem = context.measurementSystem
                val fromSystem = from.system ?: return null

                // UK and US share imperial units for these purposes; only the volume tables differ.
                val sameFamily = fromSystem == localeSystem ||
                    (fromSystem != MeasurementSystem.SI && localeSystem != MeasurementSystem.SI)

                val targetSystem = if (sameFamily) {
                    if (localeSystem == MeasurementSystem.SI) MeasurementSystem.US else MeasurementSystem.SI
                } else {
                    localeSystem
                }

                val candidates = UnitTables.preferredFor(from.dimension, targetSystem)
                    .ifEmpty { UnitTables.preferredFor(from.dimension, MeasurementSystem.SI) }
                return pickByMagnitude(candidates, base)
            }
        }
    }

    /**
     * The unit one step below [from], or one step up when it is already the smallest default.
     */
    private fun stepDown(from: UnitDef, ladder: List<UnitDef>): UnitDef? {
        if (ladder.isEmpty()) return null
        val index = ladder.indexOfFirst { it.id == from.id }
        return when {
            index > 0 -> ladder[index - 1]
            // Already the smallest, or excluded from defaults entirely: go the other way.
            index == 0 -> ladder.getOrNull(1)
            else -> ladder.first()
        }
    }

    /** At most three: "8 h 19 min 30 s" is readable, a fourth term is not. */
    private const val MAX_DURATION_PARTS = 3

    /**
     * Durations read better spread across units than as a fraction of one: 499.5 min is
     * "8 h 19 min 30 s", not "8.325 h".
     *
     * Composition starts at the target unit and works down, so an explicit request is still
     * honoured — "2 h to min" gives 120 min rather than silently becoming 2 h again. Leading zero
     * terms are dropped, which is what makes "30 s" read as itself rather than "0 min 30 s".
     */
    private fun composeDuration(totalSeconds: Double, target: UnitDef, context: AnswerContext): String {
        val ladder = UnitTables.ladderFor(Dimension.DURATION).reversed()
        val start = ladder.indexOfFirst { it.id == target.id }.coerceAtLeast(0)

        val sign = if (totalSeconds < 0) "-" else ""
        var remaining = abs(totalSeconds)
        val parts = mutableListOf<String>()

        for (index in start until ladder.size) {
            val unit = ladder[index]
            // The final term absorbs whatever is left, so nothing is silently discarded.
            val isFinal = index == ladder.lastIndex || parts.size == MAX_DURATION_PARTS - 1
            val amount = remaining / unit.factor

            if (isFinal) {
                if (amount > 0 || parts.isEmpty()) {
                    parts.add("${AnswerFormat.number(amount, context.locale)} ${unit.symbol}")
                }
                break
            }

            val whole = floor(amount)
            if (whole >= 1) {
                parts.add("${AnswerFormat.number(whole, context.locale)} ${unit.symbol}")
                remaining -= whole * unit.factor
            }
            // Floating-point residue rather than a real remainder.
            if (remaining < 1e-9) break
        }

        if (parts.isEmpty()) parts.add("0 ${ladder[start].symbol}")
        return sign + parts.joinToString(" ")
    }

    /**
     * The unit that puts the number in a comfortable range — the largest whose value is still at
     * least 1, so 5ft reads as 1.524 m rather than 1524 mm or 0.0015 km.
     */
    private fun pickByMagnitude(candidates: List<UnitDef>, base: Double): UnitDef? {
        if (candidates.isEmpty()) return null
        val magnitude = abs(base)
        if (magnitude == 0.0) return candidates.firstOrNull { it.factor == 1.0 } ?: candidates.first()

        return candidates.lastOrNull { abs(it.fromBase(base)) >= 1.0 } ?: candidates.first()
    }
}
