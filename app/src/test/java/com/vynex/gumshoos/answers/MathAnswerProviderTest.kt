package com.vynex.gumshoos.answers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class MathAnswerProviderTest {

    private val india = AnswerContext(
        measurementSystem = MeasurementSystem.SI,
        currencyCode = "INR",
        zoneId = "Asia/Kolkata",
        locale = Locale.UK, // en-IN grouping differs; UK keeps the tests about maths, not lakhs
    )

    private fun result(query: String): String? =
        AnswerProviders.answerFor(query, india)?.headline

    // --- arithmetic ------------------------------------------------------------------------------

    @Test
    fun `adds`() = assertEquals("4", result("2+2"))

    @Test
    fun `respects precedence`() = assertEquals("14", result("2 + 3 * 4"))

    @Test
    fun `parentheses override precedence`() = assertEquals("20", result("(2 + 3) * 4"))

    @Test
    fun `divides`() = assertEquals("2.5", result("5/2"))

    @Test
    fun `powers are right associative`() = assertEquals("512", result("2^3^2"))

    @Test
    fun `unary minus`() = assertEquals("-1", result("2 - 3"))

    @Test
    fun `modulo uses the mod keyword`() = assertEquals("1", result("10 mod 3"))

    @Test
    fun `handles thousands separators in input`() = assertEquals("2,000", result("1,000 + 1,000"))

    // --- functions and constants -----------------------------------------------------------------

    @Test
    fun `sqrt with parentheses`() = assertEquals("4", result("sqrt(16)"))

    @Test
    fun `sqrt without parentheses`() = assertEquals("4", result("sqrt 16"))

    @Test
    fun `pi is available`() {
        val value = result("pi * 2")!!.toDouble()
        assertEquals(6.283, value, 0.001)
    }

    // --- percentages -----------------------------------------------------------------------------

    @Test
    fun `percent of`() = assertEquals("5", result("10% of 50"))

    @Test
    fun `adding a percent is relative to the left operand`() = assertEquals("55", result("50 + 10%"))

    @Test
    fun `subtracting a percent is relative too`() = assertEquals("45", result("50 - 10%"))

    @Test
    fun `a bare percent is a hundredth`() = assertEquals("0.1", result("10% * 1"))

    // --- formatting ------------------------------------------------------------------------------

    @Test
    fun `floating point noise is trimmed`() {
        // The canonical case: a calculator showing 0.30000000000000004 looks broken.
        assertEquals("0.3", result("0.1 + 0.2"))
    }

    @Test
    fun `large results are grouped`() = assertEquals("1,000,000", result("1000 * 1000"))

    // --- trigger discipline ----------------------------------------------------------------------
    // The important half: these must NOT be claimed, or ordinary app searches break.

    @Test
    fun `a bare number is not maths`() = assertNull(result("2048"))

    @Test
    fun `a negative bare number is not maths`() = assertNull(result("-5"))

    @Test
    fun `an app name is not maths`() = assertNull(result("spotify"))

    @Test
    fun `a function name alone is not maths`() = assertNull(result("cos"))

    @Test
    fun `a hyphenated app name is not maths`() = assertNull(result("jio-dhan"))

    @Test
    fun `an x inside a word is not multiplication`() = assertNull(result("xbox"))

    @Test
    fun `a bare number with grouping is not maths`() = assertNull(result("1,000"))

    @Test
    fun `nonsense is not maths`() = assertNull(result("((("))

    // --- explicit forcing ------------------------------------------------------------------------

    @Test
    fun `a leading equals still evaluates`() = assertEquals("4", result("=2+2"))

    @Test
    fun `the answer carries the expression as detail`() {
        val answer = AnswerProviders.answerFor("2+2", india)
        assertNotNull(answer)
        assertEquals("math", answer!!.providerId)
        assertEquals("2+2 =", answer.detail)
        assertEquals("4", answer.copyText)
    }
}
