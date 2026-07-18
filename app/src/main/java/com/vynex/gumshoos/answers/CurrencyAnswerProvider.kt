package com.vynex.gumshoos.answers

import java.text.NumberFormat
import java.util.Currency

/**
 * Currency conversion, defaulting to the currency the device is set to.
 *
 * The default is the point: someone in India typing `$5` wants rupees without saying so. `$5 to
 * CAD` always wins when it is said.
 *
 * With no rates at all — offline and never yet fetched — this returns null rather than guessing.
 * An invented exchange rate is far worse than no answer.
 */
object CurrencyAnswerProvider : AnswerProvider {

    override val id = "currency"
    override val label = "Currency"

    /**
     * `$` is deliberately not hard-wired to USD. A Canadian device means CAD by it, an Australian
     * one AUD — the same reasoning as feet converting to metres on a metric phone.
     */
    private val dollarCountries = setOf(
        "CAD", "AUD", "SGD", "NZD", "HKD", "TWD", "MXN", "BND", "FJD", "JMD", "NAD", "TTD",
    )

    private val symbols: Map<String, String> = mapOf(
        "€" to "EUR", "£" to "GBP", "¥" to "JPY", "₹" to "INR", "₽" to "RUB",
        "₩" to "KRW", "₺" to "TRY", "₪" to "ILS", "₦" to "NGN", "₫" to "VND",
        "₱" to "PHP", "฿" to "THB", "R$" to "BRL", "CHF" to "CHF", "kr" to "SEK",
        "A$" to "AUD", "C$" to "CAD", "S$" to "SGD", "HK$" to "HKD", "NZ$" to "NZD",
        "RM" to "MYR", "Rp" to "IDR", "₴" to "UAH", "د.إ" to "AED",
    )

    private val names: Map<String, String> = mapOf(
        "dollar" to "USD", "dollars" to "USD", "usd" to "USD", "bucks" to "USD",
        "euro" to "EUR", "euros" to "EUR",
        "pound" to "GBP", "pounds" to "GBP", "sterling" to "GBP", "quid" to "GBP",
        "rupee" to "INR", "rupees" to "INR",
        "yen" to "JPY", "yuan" to "CNY", "rmb" to "CNY", "won" to "KRW",
        "ruble" to "RUB", "roubles" to "RUB", "rubles" to "RUB",
        "dirham" to "AED", "dirhams" to "AED", "riyal" to "SAR", "riyals" to "SAR",
        "franc" to "CHF", "francs" to "CHF", "peso" to "MXN", "pesos" to "MXN",
        "real" to "BRL", "reais" to "BRL", "rand" to "ZAR", "shekel" to "ILS",
        "lira" to "TRY", "baht" to "THB", "ringgit" to "MYR", "taka" to "BDT",
    )

    private val PATTERN = Regex(
        """^\s*([^\d\s]{1,3})?\s*(-?[\d,]+(?:\.\d+)?)\s*([a-zA-Z$€£¥₹₽₩₺₪₦₫₱฿]{1,10})?""" +
            """(?:\s*(?:to|in|as|>|->)\s*([a-zA-Z$€£¥₹₽₩₺₪₦₫₱฿.]{1,20}))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    override fun answer(query: String, context: AnswerContext): Answer? {
        val rates = context.rates ?: return null
        val match = PATTERN.matchEntire(query) ?: return null

        val amount = match.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null

        // Either a leading symbol or a trailing code, never both, and never neither.
        val prefix = match.groupValues[1].takeIf { it.isNotEmpty() }
        val suffix = match.groupValues[3].takeIf { it.isNotEmpty() }
        if ((prefix == null) == (suffix == null)) return null

        val from = codeFor(prefix ?: suffix!!, context, rates) ?: return null

        val to = match.groupValues[4].takeIf { it.isNotEmpty() }
            ?.let { codeFor(it, context, rates) ?: return null }
            ?: defaultTarget(from, context)
        if (to == from) return null

        val converted = rates.convert(amount, from, to) ?: return null
        return build(amount, from, to, converted, rates, context)
    }

    /**
     * Where a conversion lands when unsaid: the device's own currency, or dollars when the amount
     * is already in it.
     */
    private fun defaultTarget(from: String, context: AnswerContext): String = when {
        from != context.currencyCode -> context.currencyCode
        context.currencyCode != "USD" -> "USD"
        else -> "EUR"
    }

    private fun codeFor(token: String, context: AnswerContext, rates: RateTable): String? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed == "$") {
            return context.currencyCode.takeIf { it in dollarCountries } ?: "USD"
        }
        symbols[trimmed]?.let { return it }
        names[trimmed.lowercase()]?.let { return it }

        val upper = trimmed.uppercase()
        // Only codes the rate table actually carries, so "5 gap" is never read as a currency.
        if (upper.length == 3 && (upper == rates.base || upper in rates.rates)) return upper
        return null
    }

    private fun build(
        amount: Double,
        from: String,
        to: String,
        converted: Double,
        rates: RateTable,
        context: AnswerContext,
    ): Answer {
        val headline = money(converted, to, context)
        val unitRate = rates.convert(1.0, from, to) ?: 1.0

        // Always shown, and as elapsed time rather than a date: the question being answered is
        // whether the number can still be trusted, and "6 days ago" says that where "13 Jul" makes
        // the reader work it out.
        val age = AnswerFormat.relativeTime(rates.fetchedAtMillis, context.clock.millis())

        val detail = "${money(amount, from, context)}  ·  " +
            "1 $from = ${AnswerFormat.rate(unitRate, context.locale)} $to  ·  $age"

        return Answer(
            providerId = id,
            headline = headline,
            detail = detail,
            copyText = headline,
        )
    }

    private fun money(value: Double, code: String, context: AnswerContext): String =
        runCatching {
            NumberFormat.getCurrencyInstance(context.locale).apply {
                currency = Currency.getInstance(code)
                maximumFractionDigits = if (value < 1.0) 4 else 2
            }.format(value)
        }.getOrElse { "${AnswerFormat.number(value, context.locale)} $code" }
}
