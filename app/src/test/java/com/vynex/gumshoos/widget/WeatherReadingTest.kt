package com.vynex.gumshoos.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weather parser, against real reply shapes.
 *
 * The negatives are the point. This feeds a widget slot that is otherwise blank, so the failure to
 * avoid is a confident wrong number — `0°C` drawn from an error reply reads exactly like a cold
 * morning. Every malformed shape has to come out as null.
 *
 * These run against the real `org.json` from the test-only dependency rather than the framework
 * stub, so what is exercised is the shipping code path. On the stub every method throws `Stub!`,
 * the parser catches it, and all of this would pass while proving nothing.
 */
class WeatherReadingTest {

    // Trimmed from the documented sample: the fields the parser actually reads.
    private val ok = """
        {"coord":{"lon":10.99,"lat":44.34},
         "weather":[{"id":501,"main":"Rain","description":"moderate rain","icon":"10d"}],
         "main":{"temp":18.48,"feels_like":18.74,"humidity":64},
         "name":"Zocca","cod":200}
    """.trimIndent()

    @Test
    fun `reads a normal reply`() {
        val r = WeatherReading.parse(ok)!!
        assertEquals(18.48, r.tempC, 0.001)
        assertEquals("10d", r.icon)
        assertEquals("moderate rain", r.description)
        assertEquals("Zocca", r.place)
    }

    @Test
    fun `formats rounded, with the unit on it`() {
        val r = WeatherReading.parse(ok)!!
        assertEquals("18°C", r.format(WeatherUnit.CELSIUS))
        assertEquals("65°F", r.format(WeatherUnit.FAHRENHEIT))
    }

    @Test
    fun `converts celsius to fahrenheit exactly`() {
        val freezing = WeatherReading(0.0, "", "", "")
        assertEquals(32.0, freezing.temperature(WeatherUnit.FAHRENHEIT), 0.0001)
        assertEquals(212.0, WeatherReading(100.0, "", "", "").temperature(WeatherUnit.FAHRENHEIT), 0.0001)
        // The one that catches a sign error, since it is the same number in both scales.
        assertEquals(-40.0, WeatherReading(-40.0, "", "", "").temperature(WeatherUnit.FAHRENHEIT), 0.0001)
    }

    @Test
    fun `a negative temperature survives`() {
        assertEquals("-7°C", WeatherReading.parse(
            """{"weather":[{"icon":"13n"}],"main":{"temp":-7.2},"name":"Oslo","cod":200}"""
        )!!.format(WeatherUnit.CELSIUS))
    }

    // --- the shapes that must not produce a number ------------------------------------------------

    @Test
    fun `a bad key is not a reading`() {
        // 401 arrives as well-formed JSON with no main block. optDouble would have made this 0°C.
        assertNull(WeatherReading.parse(
            """{"cod":401,"message":"Invalid API key. Please see https://openweathermap.org/faq"}"""
        ))
    }

    @Test
    fun `a rate limit is not a reading`() {
        assertNull(WeatherReading.parse("""{"cod":429,"message":"Your account is temporary blocked"}"""))
    }

    @Test
    fun `city not found is not a reading, and its cod is a string`() {
        // The trap: 200 comes back as the number 200, this comes back as the string "404". optInt
        // returns 0 for a string it cannot coerce and getInt throws, so cod is compared as text.
        assertNull(WeatherReading.parse("""{"cod":"404","message":"city not found"}"""))
    }

    @Test
    fun `a reply with no temperature is not a reading`() {
        assertNull(WeatherReading.parse("""{"main":{"humidity":64},"name":"Zocca","cod":200}"""))
        assertNull(WeatherReading.parse("""{"name":"Zocca","cod":200}"""))
    }

    @Test
    fun `a non-numeric temperature is not a reading`() {
        assertNull(WeatherReading.parse("""{"main":{"temp":"warm"},"cod":200}"""))
    }

    @Test
    fun `malformed input is not a reading`() {
        assertNull(WeatherReading.parse(""))
        assertNull(WeatherReading.parse("not json"))
        assertNull(WeatherReading.parse("[]"))
        assertNull(WeatherReading.parse("null"))
        assertNull(WeatherReading.parse("""{"main":null,"cod":200}"""))
    }

    @Test
    fun `a reading without a condition block is still a temperature`() {
        // All the bar draws today is the number, so a missing weather array is not fatal.
        val r = WeatherReading.parse("""{"main":{"temp":9.0},"cod":200}""")!!
        assertEquals(9.0, r.tempC, 0.001)
        assertEquals("", r.icon)
    }

    @Test
    fun `unit ids are what the API expects, and survive an unknown one`() {
        // These strings go straight into the query as units=, so they are not free to change.
        assertEquals("metric", WeatherUnit.CELSIUS.id)
        assertEquals("imperial", WeatherUnit.FAHRENHEIT.id)
        assertEquals(WeatherUnit.CELSIUS, WeatherUnit.fromId(null))
        assertEquals(WeatherUnit.CELSIUS, WeatherUnit.fromId("kelvin"))
    }

    // --- condition icons ---------------------------------------------------------------------------

    @Test
    fun `every documented code has a mark, day and night`() {
        val conditions = listOf("01", "02", "03", "04", "09", "10", "11", "13", "50")
        conditions.forEach { c ->
            assertTrue("no day mark for $c", WeatherIcons.forCode("${c}d") != null)
            assertTrue("no night mark for $c", WeatherIcons.forCode("${c}n") != null)
        }
        assertEquals(conditions.size * 2, WeatherIcons.codes.size)
    }

    @Test
    fun `only clear sky and few clouds differ between day and night`() {
        // The rest share a drawable on purpose: drizzle looks the same at midnight. If a third
        // pair ever diverges it should be a deliberate edit, not a table that drifted.
        val differ = WeatherIcons.codes
            .filter { it.endsWith("d") }
            .filter { WeatherIcons.forCode(it) != WeatherIcons.forCode(it.dropLast(1) + "n") }
            .sorted()
        assertEquals(listOf("01d", "02d"), differ)
    }

    @Test
    fun `an unknown code draws no mark rather than a guess`() {
        assertNull(WeatherIcons.forCode("99x"))
        assertNull(WeatherIcons.forCode(""))
        assertNull(WeatherIcons.forCode(null))
        // Case matters: the API sends lowercase and a table lookup must not quietly accept more.
        assertNull(WeatherIcons.forCode("01D"))
    }
}
