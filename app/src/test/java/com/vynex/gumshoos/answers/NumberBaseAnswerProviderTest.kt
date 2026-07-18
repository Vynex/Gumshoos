package com.vynex.gumshoos.answers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NumberBaseAnswerProviderTest {

    private val ctx = AnswerContext(
        measurementSystem = MeasurementSystem.SI,
        currencyCode = "INR",
        zoneId = "UTC",
        locale = Locale.UK,
    )

    private fun result(query: String) = AnswerProviders.answerFor(query, ctx)?.headline
    private fun detail(query: String) = AnswerProviders.answerFor(query, ctx)?.detail

    // --- prefixed literals default to decimal ----------------------------------------------------

    @Test
    fun `hex to decimal`() = assertEquals("31", result("0x1F"))

    @Test
    fun `binary to decimal`() = assertEquals("10", result("0b1010"))

    @Test
    fun `octal to decimal`() = assertEquals("15", result("0o17"))

    @Test
    fun `hex is case insensitive`() = assertEquals("255", result("0xff"))

    // --- explicit targets ------------------------------------------------------------------------

    @Test
    fun `decimal to hex`() = assertEquals("0xFF", result("255 to hex"))

    @Test
    fun `decimal to binary`() = assertEquals("0b11111111", result("255 to binary"))

    @Test
    fun `decimal to octal`() = assertEquals("0o377", result("255 to octal"))

    @Test
    fun `between non-decimal bases`() = assertEquals("0x1F", result("0b11111 to hex"))

    @Test
    fun `base numbers work as names`() = assertEquals("0b1010", result("10 to base2"))

    // --- presentation ----------------------------------------------------------------------------

    @Test
    fun `the other bases are shown alongside`() {
        // The target is the headline, so the detail carries the three it isn't.
        assertEquals("31", result("0x1F"))
        val detail = detail("0x1F")!!
        assertTrue(detail.contains("0b11111"))
        assertTrue(detail.contains("0o37"))
        assertTrue(detail.contains("0x1F"))
    }

    @Test
    fun `negatives keep their sign`() = assertEquals("-0xFF", result("-255 to hex"))

    // --- trigger discipline ----------------------------------------------------------------------

    @Test
    fun `a bare number is not a base conversion`() {
        // 1010 is a plausible app name; guessing it might be binary would break searching for it.
        assertNull(result("1010"))
        assertNull(result("255"))
    }

    @Test
    fun `an invalid digit for the base is refused`() {
        // 2 is not a binary digit — better to show nothing than coerce a typo into an answer.
        assertNull(result("0b102"))
        assertNull(result("0o18"))
    }

    @Test
    fun `an unknown target is refused`() = assertNull(result("255 to klingon"))

    @Test
    fun `app names are untouched`() {
        assertNull(result("spotify"))
        assertNull(result("0xygen"))
    }
}
