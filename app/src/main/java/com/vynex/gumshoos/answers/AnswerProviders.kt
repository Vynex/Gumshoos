package com.vynex.gumshoos.answers

import java.util.Locale

/**
 * A query that carries its own answer — arithmetic, a conversion, a time somewhere else.
 *
 * @param providerId stable key, like SearchProvider.id, so settings can persist a selection
 *   without breaking when a label changes.
 */
data class Answer(
    val providerId: String,
    val headline: String,
    val detail: String?,
    val copyText: String,
)

/** Which units a locale expects. Kept independent of android.icu so this stays unit-testable. */
enum class MeasurementSystem { SI, US, UK }

/**
 * Everything a provider needs about the device, passed in rather than read from the environment.
 *
 * This is the whole reason the locale rules are testable. Providers that called
 * `Locale.getDefault()` themselves could only ever be verified on a device set to that locale,
 * and the defaults — an Indian device wanting metres and rupees — are the part most likely to be
 * wrong.
 */
data class AnswerContext(
    val measurementSystem: MeasurementSystem,
    val currencyCode: String,
    val zoneId: String,
    val locale: Locale,
    val rates: RateTable? = null,
)

/** Exchange rates relative to [base]. Null on the context when unavailable or offline. */
data class RateTable(
    val base: String,
    val rates: Map<String, Double>,
    val fetchedAtMillis: Long,
) {
    fun convert(amount: Double, from: String, to: String): Double? {
        if (from == to) return amount
        val fromRate = if (from == base) 1.0 else rates[from] ?: return null
        val toRate = if (to == base) 1.0 else rates[to] ?: return null
        return amount / fromRate * toRate
    }
}

interface AnswerProvider {
    val id: String

    /** Shown as the section heading, so it names what was recognised rather than saying "Answer". */
    val label: String

    /** Null means "not mine" — the single most important behaviour here; see [AnswerProviders]. */
    fun answer(query: String, context: AnswerContext): Answer?
}

/**
 * Dispatches a query to the first provider that recognises it.
 *
 * The overriding concern is *not* hijacking ordinary searches. A provider must return null unless
 * it is confident: "2048" is a game, not arithmetic, and "ft" is not a conversion. Every provider
 * requires a number plus corroborating evidence — an operator, a unit token, a currency symbol —
 * before it will claim a query.
 *
 * Runs on every keystroke, so matching stays a cheap scan over a short string.
 */
object AnswerProviders {

    /** Order matters: the first non-null wins, so the most specific patterns come first. */
    val all: List<AnswerProvider> = listOf(
        MathAnswerProvider,
        // Before units: "0x1F" would otherwise be scanned for a unit named "x1F", and "255 to hex"
        // for one named "to". Both fail, but ordering the specific pattern first says why.
        NumberBaseAnswerProvider,
        UnitAnswerProvider,
    )

    var enabledIds: Set<String> = all.map { it.id }.toSet()

    fun answerFor(query: String, context: AnswerContext): Answer? {
        if (query.isBlank()) return null
        val trimmed = query.trim()
        // A bare number is never an answer — that is how "2048" stays a game.
        if (BARE_NUMBER.matches(trimmed)) return null

        return all.asSequence()
            .filter { it.id in enabledIds }
            .firstNotNullOfOrNull { it.answer(trimmed, context) }
    }

    /** Section heading for an answer, so the UI names what was recognised. */
    fun labelFor(providerId: String): String =
        all.firstOrNull { it.id == providerId }?.label ?: "Answer"

    private val BARE_NUMBER = Regex("""^[+-]?\d[\d,\s]*(\.\d+)?$""")
}
