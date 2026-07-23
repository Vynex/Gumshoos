package com.vynex.gumshoos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison rules an update badge turns on, and the parse cases that must fail rather than
 * guess — a phantom "update available" is the failure mode worth testing hardest.
 */
class SemverTest {

    private fun parse(raw: String) = requireNotNull(Semver.parse(raw)) { "did not parse: $raw" }

    @Test fun `parses a plain triple`() {
        assertEquals(Semver(1, 2, 3), parse("1.2.3"))
    }

    @Test fun `strips a leading v`() {
        assertEquals(Semver(1, 1, 0), parse("v1.1.0"))
        assertEquals(Semver(1, 1, 0), parse("V1.1.0"))
    }

    @Test fun `missing minor and patch read as zero`() {
        assertEquals(Semver(2, 0, 0), parse("v2"))
        assertEquals(Semver(2, 3, 0), parse("2.3"))
    }

    @Test fun `build metadata is dropped`() {
        assertEquals(Semver(1, 2, 3), parse("1.2.3+build.7"))
    }

    @Test fun `prerelease identifiers are split on dots`() {
        assertEquals(Semver(1, 2, 3, listOf("rc", "1")), parse("1.2.3-rc.1"))
    }

    @Test fun `surrounding whitespace is tolerated`() {
        assertEquals(Semver(1, 0, 0), parse("  1.0.0 "))
    }

    // --- the negatives: anything unreadable is null, never a coerced guess -----------------------

    @Test fun `rubbish is null`() {
        assertNull(Semver.parse("not-a-version"))
        assertNull(Semver.parse(""))
        assertNull(Semver.parse(null))
    }

    @Test fun `an empty prerelease identifier is rejected`() {
        assertNull(Semver.parse("1.0.0-"))
        assertNull(Semver.parse("1.0.0-rc..1"))
    }

    @Test fun `an overflowing component is rejected rather than wrapped`() {
        assertNull(Semver.parse("99999999999999999999.0.0"))
    }

    // --- precedence ------------------------------------------------------------------------------

    @Test fun `newer core outranks older`() {
        assertTrue(parse("1.2.0") > parse("1.1.9"))
        assertTrue(parse("2.0.0") > parse("1.9.9"))
        assertTrue(parse("1.0.1") > parse("1.0.0"))
    }

    @Test fun `equal cores are equal`() {
        assertEquals(0, parse("1.2.3").compareTo(parse("v1.2.3")))
    }

    @Test fun `a prerelease ranks below its own release`() {
        assertTrue(parse("1.2.0-rc.1") < parse("1.2.0"))
        assertTrue(parse("1.2.0") > parse("1.2.0-beta"))
    }

    @Test fun `prerelease identifiers order per spec`() {
        // numeric below alphanumeric, numeric compared numerically, longer set wins when equal.
        assertTrue(parse("1.0.0-alpha") < parse("1.0.0-alpha.1"))
        assertTrue(parse("1.0.0-alpha.1") < parse("1.0.0-alpha.beta"))
        assertTrue(parse("1.0.0-rc.2") > parse("1.0.0-rc.1"))
        assertTrue(parse("1.0.0-rc.11") > parse("1.0.0-rc.2"))
    }

    @Test fun `a released tag is not newer than the same installed version`() {
        // The exact case the badge gates on: same version installed, no update.
        assertTrue(parse("v1.1.0") <= parse("1.1.0"))
        assertTrue(!(parse("v1.1.0") > parse("1.1.0")))
    }
}
