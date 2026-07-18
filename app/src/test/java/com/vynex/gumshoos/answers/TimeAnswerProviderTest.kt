package com.vynex.gumshoos.answers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class TimeAnswerProviderTest {

    /** 2026-07-18T12:00:00Z — a fixed instant, so "now" is reproducible. */
    private val fixed = Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneId.of("UTC"))

    private fun context(zone: String = "Asia/Kolkata") = AnswerContext(
        measurementSystem = MeasurementSystem.SI,
        currencyCode = "INR",
        zoneId = zone,
        locale = Locale.UK,
        clock = fixed,
    )

    private fun result(query: String, ctx: AnswerContext = context()) =
        AnswerProviders.answerFor(query, ctx)?.headline

    private fun detail(query: String, ctx: AnswerContext = context()) =
        AnswerProviders.answerFor(query, ctx)?.detail

    // --- current time elsewhere ------------------------------------------------------------------

    @Test
    fun `time in a city`() {
        // 12:00 UTC is 21:00 in Tokyo.
        assertEquals("21:00", result("time in tokyo"))
    }

    @Test
    fun `now in a zone abbreviation`() = assertEquals("12:00", result("now in utc"))

    @Test
    fun `place then time`() = assertEquals("21:00", result("tokyo time"))

    @Test
    fun `multi-word cities resolve`() {
        // 12:00 UTC is 08:00 in New York.
        assertEquals("08:00", result("time in new york"))
    }

    @Test
    fun `country names resolve`() = assertEquals("17:30", result("time in india"))

    @Test
    fun `a full zone id resolves`() = assertEquals("21:00", result("time in Asia/Tokyo"))

    // --- converting a given time -----------------------------------------------------------------

    @Test
    fun `a time in another place`() {
        // 15:00 in Kolkata is 09:30 UTC, so 10:30 in London during BST.
        assertEquals("10:30", result("3pm in london"))
    }

    @Test
    fun `between two explicit zones`() {
        // 15:00 IST resolves to Kolkata on an Indian device; that is 02:30 in Los Angeles.
        assertEquals("02:30", result("3pm IST to PST"))
    }

    @Test
    fun `24 hour input`() = assertEquals("10:30", result("15:00 in london"))

    @Test
    fun `crossing midnight shows the date`() {
        // 23:00 in Kolkata is already the next day in Tokyo.
        val answer = result("11pm in tokyo")!!
        assertTrue("expected a date alongside the time, got '$answer'", answer.contains(","))
    }

    // --- the resolved zone is always visible -----------------------------------------------------

    @Test
    fun `the detail names the zone that was chosen`() {
        // Abbreviations are ambiguous, so a wrong guess must be visible rather than silent.
        assertEquals("Asia/Tokyo", detail("time in tokyo"))
        assertTrue(detail("3pm IST to PST")!!.contains("America/Los Angeles"))
    }

    @Test
    fun `an ambiguous abbreviation prefers the device's own zone`() {
        // IST is India, Israel and Ireland. An Israeli device should not be told India's time.
        val israeli = context(zone = "Asia/Jerusalem")
        assertTrue(detail("3pm IST to utc", israeli)!!.contains("Asia/Jerusalem"))
        assertTrue(detail("3pm IST to utc", context())!!.contains("Asia/Kolkata"))
    }

    // --- trigger discipline ----------------------------------------------------------------------

    @Test
    fun `an unknown place is not a time`() = assertNull(result("time in spotify"))

    @Test
    fun `time alone is not a time`() = assertNull(result("time"))

    @Test
    fun `screen time is not a timezone`() {
        // The "<place> time" pattern is two bare words, so it must require a resolvable place.
        assertNull(result("screen time"))
    }

    @Test
    fun `an app name is untouched`() {
        assertNull(result("spotify"))
        assertNull(result("instagram"))
    }

    @Test
    fun `a duration still belongs to the unit provider`() {
        // "min" is not a place, so this falls through to units rather than being claimed here.
        assertEquals("90 min", result("1.5 h to min"))
    }

    @Test
    fun `an impossible time is refused`() = assertNull(result("25:99 in tokyo"))
}
