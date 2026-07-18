package com.vynex.gumshoos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {

    private fun rank(query: String, labels: List<String>): List<String> =
        labels.mapNotNull { label ->
            AppMatcher.score(query, label, AppMatcher.initialsOf(label))?.let { label to it }
        }.sortedByDescending { it.second }.map { it.first }

    private fun matches(query: String, label: String): Boolean =
        AppMatcher.score(query, label, AppMatcher.initialsOf(label)) != null

    // --- initials -------------------------------------------------------------------------------

    @Test
    fun `initials from spaced words`() {
        assertEquals("bb", AppMatcher.initialsOf("Big Basket"))
    }

    @Test
    fun `initials from internal capitals`() {
        assertEquals("bms", AppMatcher.initialsOf("BookMyShow"))
    }

    @Test
    fun `initials mix words and capitals`() {
        assertEquals("wa", AppMatcher.initialsOf("WhatsApp"))
        assertEquals("gp", AppMatcher.initialsOf("Google Pay"))
        // Both rules at once: two tokens, and a capital inside the second.
        assertEquals("gpm", AppMatcher.initialsOf("Google PlayMusic"))
    }

    @Test
    fun `initials ignore punctuation`() {
        assertEquals("jd", AppMatcher.initialsOf("Jio-Dhan"))
    }

    // --- the cases from the brief ---------------------------------------------------------------

    @Test
    fun `bb finds Big Basket`() {
        assertTrue(matches("bb", "Big Basket"))
    }

    @Test
    fun `bms finds BookMyShow`() {
        assertTrue(matches("bms", "BookMyShow"))
    }

    // --- ranking --------------------------------------------------------------------------------

    @Test
    fun `prefix beats initials beats substring`() {
        val ranked = rank("ma", listOf("Telegram Alpha", "Maps", "My App"))
        assertEquals("Maps", ranked.first())
    }

    @Test
    fun `shorter label wins within a tier`() {
        val ranked = rank("map", listOf("Maps Go Navigation", "Maps"))
        assertEquals("Maps", ranked.first())
    }

    @Test
    fun `initials outrank a loose subsequence`() {
        val ranked = rank("bms", listOf("Beams Radio", "BookMyShow"))
        assertEquals("BookMyShow", ranked.first())
    }

    // --- fuzzy ----------------------------------------------------------------------------------

    @Test
    fun `subsequence matches with gaps`() {
        assertTrue(matches("bkmy", "BookMyShow"))
    }

    @Test
    fun `tight subsequence outranks a scattered one`() {
        val ranked = rank("act", listOf("A Certain Thing", "Actions"))
        assertEquals("Actions", ranked.first())
    }

    @Test
    fun `case is ignored`() {
        assertTrue(matches("BOOK", "BookMyShow"))
        assertTrue(matches("BMS", "BookMyShow"))
    }

    @Test
    fun `unrelated query does not match`() {
        assertNull(AppMatcher.score("zzzz", "BookMyShow", AppMatcher.initialsOf("BookMyShow")))
    }

    @Test
    fun `empty query matches everything`() {
        assertNotNull(AppMatcher.score("", "Anything", "a"))
    }
}
