package com.vynex.gumshoos.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the widget colour picker's hex field accepts. */
class ColorHexTest {

    @Test
    fun `six digits with a hash`() {
        assertEquals(0xFF3366CC.toInt(), ColorHex.parse("#3366CC"))
    }

    @Test
    fun `six digits without a hash`() {
        assertEquals(0xFF3366CC.toInt(), ColorHex.parse("3366CC"))
    }

    @Test
    fun `case does not matter`() {
        assertEquals(ColorHex.parse("#ABCDEF"), ColorHex.parse("#abcdef"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(0xFF3366CC.toInt(), ColorHex.parse("  #3366CC  "))
    }

    @Test
    fun `six digits gain full alpha`() {
        // Without this a six-digit colour parses to alpha 0 and is silently invisible, which reads
        // as the picker doing nothing at all.
        assertEquals(0xFF, (ColorHex.parse("#000000")!! ushr 24) and 0xFF)
    }

    @Test
    fun `eight digits keep their own alpha`() {
        assertEquals(0x80.toInt(), (ColorHex.parse("#80FF0000")!! ushr 24) and 0xFF)
    }

    @Test
    fun `nonsense is rejected rather than guessed at`() {
        assertNull(ColorHex.parse(""))
        assertNull(ColorHex.parse("#"))
        assertNull(ColorHex.parse("#12345"))
        assertNull(ColorHex.parse("#1234567"))
        assertNull(ColorHex.parse("#GGGGGG"))
        assertNull(ColorHex.parse("blue"))
    }

    @Test
    fun `formatting drops alpha and round-trips`() {
        // The field shows RGB only: opacity is the slider's business, and showing alpha here would
        // give two controls over the same value.
        assertEquals("#3366CC", ColorHex.format(0x803366CC.toInt()))
        assertEquals(0xFF3366CC.toInt(), ColorHex.parse(ColorHex.format(0xFF3366CC.toInt())))
    }
}
