package com.vynex.gumshoos.answers

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

class CurrencyAnswerProviderTest {

    private companion object {
        const val FIXED_NOW = 1_784_000_000_000L
    }

    /** Fixed rates, so the tests exercise conversion rather than the network. */
    private val rates = RateTable(
        base = "INR",
        rates = mapOf(
            "INR" to 1.0,
            "USD" to 0.012,
            "EUR" to 0.011,
            "GBP" to 0.0095,
            "CAD" to 0.016,
            "AED" to 0.044,
            "JPY" to 1.9,
        ),
        fetchedAtMillis = System.currentTimeMillis(),
    )

    private fun context(currency: String = "INR") = AnswerContext(
        measurementSystem = MeasurementSystem.SI,
        currencyCode = currency,
        zoneId = "Asia/Kolkata",
        locale = Locale.UK,
        rates = rates,
    )

    private fun result(query: String, ctx: AnswerContext = context()) =
        AnswerProviders.answerFor(query, ctx)?.headline

    private fun detail(query: String, ctx: AnswerContext = context()) =
        AnswerProviders.answerFor(query, ctx)?.detail

    private fun amountOf(text: String): Double =
        text.filter { it.isDigit() || it == '.' }.toDouble()

    // --- locale defaults: the point of the module ------------------------------------------------

    @Test
    fun `an Indian device converts dollars to rupees by default`() {
        // $5 at 0.012 USD per INR is about 416.67.
        val headline = result("${'$'}5")!!
        assertTrue("expected rupees, got '$headline'", headline.contains("₹"))
        assertTrue(amountOf(headline) in 416.0..417.0)
    }

    @Test
    fun `the local currency converts to dollars instead`() {
        // Converting rupees to rupees would say nothing.
        val headline = result("₹500")!!
        assertTrue("expected dollars, got '$headline'", headline.contains("${'$'}"))
    }

    @Test
    fun `a dollar sign means the local dollar where there is one`() {
        // A Canadian device typing $5 means CAD, not USD. Being already local, it converts onward
        // to dollars — the same rule that sends local units to the other system.
        val headline = result("${'$'}5", context("CAD"))!!
        assertTrue("expected a USD amount, got '$headline'", amountOf(headline) in 3.7..3.8)

        // An Indian device reads the same input as USD, so it lands on rupees instead.
        assertTrue(result("${'$'}5", context("INR"))!!.contains("₹"))
    }

    @Test
    fun `an explicit target beats the default`() {
        val headline = result("${'$'}5 to CAD")!!
        assertTrue(amountOf(headline) in 6.5..6.7)
    }

    // --- input forms -----------------------------------------------------------------------------

    @Test
    fun `a trailing code works`() = assertNotNull(result("5 usd"))

    @Test
    fun `a currency name works`() = assertNotNull(result("5 dollars"))

    @Test
    fun `codes are case insensitive`() = assertNotNull(result("5 USD to INR"))

    @Test
    fun `named targets work`() = assertNotNull(result("₹500 in dollars"))

    @Test
    fun `thousands separators are accepted`() = assertNotNull(result("${'$'}1,000"))

    // --- presentation ----------------------------------------------------------------------------

    @Test
    fun `the rate and its age are always shown`() {
        // A number that looks authoritative while being a week old is worse than one that says so.
        val detail = detail("${'$'}5")!!
        assertTrue(detail.contains("1 USD ="))
        assertTrue(detail.contains("ago") || detail.contains("just now"))
    }

    @Test
    fun `the rate is limited to four decimals`() {
        // Ten decimals of an exchange rate read as noise beside the converted amount.
        val detail = detail("${'$'}5")!!
        val rate = Regex("""1 USD = ([\d.,]+)""").find(detail)!!.groupValues[1]
        val decimals = rate.substringAfter('.', "").length
        assertTrue("expected at most 4 decimals, got '${'$'}rate'", decimals <= 4)
    }

    @Test
    fun `age is shown as elapsed time rather than a date`() {
        fun ageFor(millisAgo: Long): String {
            val ctx = context().copy(
                rates = rates.copy(fetchedAtMillis = FIXED_NOW - millisAgo),
                clock = java.time.Clock.fixed(
                    java.time.Instant.ofEpochMilli(FIXED_NOW),
                    java.time.ZoneId.of("UTC"),
                ),
            )
            return detail("${'$'}5", ctx)!!
        }

        assertTrue(ageFor(TimeUnit.MINUTES.toMillis(5)).contains("5 minutes ago"))
        assertTrue(ageFor(TimeUnit.HOURS.toMillis(3)).contains("3 hours ago"))
        assertTrue(ageFor(TimeUnit.DAYS.toMillis(6)).contains("6 days ago"))
        assertTrue(ageFor(TimeUnit.SECONDS.toMillis(20)).contains("just now"))
        // Singulars read correctly.
        assertTrue(ageFor(TimeUnit.HOURS.toMillis(1)).contains("1 hour ago"))
    }

    // --- no rates --------------------------------------------------------------------------------

    @Test
    fun `without rates there is no answer rather than a guess`() {
        // Offline and never fetched: an invented exchange rate is far worse than nothing.
        val noRates = context().copy(rates = null)
        assertNull(result("${'$'}5", noRates))
    }

    @Test
    fun `an unknown currency is refused`() = assertNull(result("5 xyz"))

    // --- trigger discipline ----------------------------------------------------------------------

    @Test
    fun `a bare number is not a currency`() = assertNull(result("500"))

    @Test
    fun `an app name is not a currency`() {
        assertNull(result("spotify"))
        assertNull(result("gmail"))
    }

    @Test
    fun `a three letter word that is not a currency code is refused`() {
        // Only codes the rate table actually carries count.
        assertNull(result("5 gap"))
    }

    @Test
    fun `a unit is not a currency`() {
        // Units are tried first, and "5 kg" must stay a mass conversion.
        assertTrue(result("5 kg")!!.contains("lb"))
    }

    @Test
    fun `a symbol and a code together are refused`() = assertNull(result("${'$'}5 usd"))
}
