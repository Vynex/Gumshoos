package com.vynex.gumshoos.answers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UnitAnswerProviderTest {

    private fun context(system: MeasurementSystem) = AnswerContext(
        measurementSystem = system,
        currencyCode = if (system == MeasurementSystem.US) "USD" else "INR",
        zoneId = "UTC",
        locale = Locale.UK,
    )

    private val india = context(MeasurementSystem.SI)
    private val america = context(MeasurementSystem.US)

    private fun result(query: String, ctx: AnswerContext = india): String? =
        AnswerProviders.answerFor(query, ctx)?.headline

    // --- explicit conversions --------------------------------------------------------------------

    @Test
    fun `feet to metres`() = assertEquals("1.524 m", result("5 ft to m"))

    @Test
    fun `in is a preposition when a unit follows`() = assertEquals("1.524 m", result("5 ft in m"))

    @Test
    fun `in is inches when nothing follows`() {
        // Same two letters, opposite meanings — the only cue is whether a unit comes after.
        assertEquals("30.48 cm", result("12 in"))
    }

    @Test
    fun `no space needed`() = assertEquals("1.524 m", result("5ft to m"))

    @Test
    fun `long unit names work`() = assertEquals("1.524 m", result("5 feet to metres"))

    @Test
    fun `kilometres to miles`() = assertEquals("6.2137119224 mi", result("10 km to mi"))

    @Test
    fun `celsius to fahrenheit`() = assertEquals("212 °F", result("100 c to f"))

    @Test
    fun `fahrenheit to celsius`() = assertEquals("37 °C", result("98.6 f to c"))

    @Test
    fun `negative temperatures`() = assertEquals("-40 °F", result("-40 c to f"))

    @Test
    fun `decimal and binary data are never conflated`() {
        // The whole point of keeping both: GB is 1000 MB, GiB is 1024 MiB, and neither borrows
        // the other's factor.
        assertEquals("1,000 MB", result("1 gb to mb"))
        assertEquals("1,024 MiB", result("1 gib to mib"))
        assertEquals("1,073.741824 MB", result("1 gib to mb"))
    }

    @Test
    fun `duration conversion`() = assertEquals("90 min", result("1.5 h to min"))

    @Test
    fun `speed conversion`() = assertEquals("62.1371192237 mph", result("100 km/h to mph"))

    @Test
    fun `area conversion`() {
        // 12 significant figures, per AnswerFormat — 107.63910416709722 rounds here.
        assertEquals("107.639104167 ft²", result("10 m2 to sqft"))
    }

    @Test
    fun `feet and inches together`() = assertEquals("1.778 m", result("5'10\""))

    // --- locale defaults: the point of the module ------------------------------------------------

    @Test
    fun `an Indian device converts feet to metres by default`() {
        assertEquals("1.524 m", result("5ft", india))
    }

    @Test
    fun `an American device converts metres to feet by default`() {
        assertEquals("16.4041994751 ft", result("5m", america))
    }

    @Test
    fun `metric input on a metric device converts to imperial`() {
        // Converting metres to metres would say nothing, so it crosses to the other system.
        assertTrue(result("5kg", india)!!.endsWith("lb"))
    }

    @Test
    fun `imperial input on an imperial device converts to metric`() {
        assertTrue(result("5lb", america)!!.endsWith("kg"))
    }

    @Test
    fun `celsius defaults to fahrenheit regardless of locale`() {
        assertEquals("212 °F", result("100c", india))
    }

    @Test
    fun `the default target is scaled sensibly`() {
        // Not 1524 mm, not 0.0015 km.
        assertEquals("1.524 m", result("5ft", india))
        // Small values step down rather than reading 0.0254 m.
        assertTrue(result("1in", india)!!.endsWith("cm"))
    }

    @Test
    fun `data scales up when the number warrants it`() {
        assertEquals("1.5 GB", result("1500 mb", india))
    }

    @Test
    fun `a duration defaults to the unit below it`() {
        // Not 60,000 ms: reaching for the smallest unit in the ladder is never what was wanted.
        assertEquals("60 s", result("1 min", india))
        assertEquals("120 min", result("2 h", india))
        assertEquals("24 h", result("1 d", india))
    }

    @Test
    fun `data defaults to the unit below it when it cannot scale up`() {
        assertEquals("1,000 MB", result("1 gb", india))
    }

    @Test
    fun `the smallest default unit steps up instead`() {
        // Nothing below seconds is offered automatically, so this goes the other way.
        assertEquals("0.5 min", result("30 s", india))
    }

    @Test
    fun `milliseconds are never an automatic target but stay reachable`() {
        assertEquals("30,000 ms", result("30 s to ms", india))
    }

    @Test
    fun `an explicit target always beats the locale default`() {
        assertEquals("60 in", result("5ft to inches", india))
    }

    // --- trigger discipline ----------------------------------------------------------------------

    @Test
    fun `a bare unit is not a conversion`() = assertNull(result("ft"))

    @Test
    fun `a bare number is not a conversion`() = assertNull(result("5"))

    @Test
    fun `an app name is not a conversion`() {
        assertNull(result("spotify"))
        assertNull(result("gmail"))
        assertNull(result("instagram"))
    }

    @Test
    fun `a number with an unknown unit is not a conversion`() = assertNull(result("1password"))

    @Test
    fun `a cross-dimension target is refused rather than guessed`() {
        // Better to show nothing than "5 ft = 5 kg".
        assertNull(result("5 ft to kg"))
    }

    @Test
    fun `an unknown target is refused`() = assertNull(result("5 ft to bananas"))

    @Test
    fun `milliseconds are not metres`() {
        // Plural stripping would fold "ms" into "m"; the alias tables must keep them apart.
        assertEquals("1 s", result("1000 ms to s"))
    }
}
