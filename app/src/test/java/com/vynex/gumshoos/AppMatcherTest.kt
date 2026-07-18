package com.vynex.gumshoos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {

    private fun score(query: String, label: String): Int? =
        AppMatcher.score(
            query,
            label,
            AppMatcher.wordInitialsOf(label),
            AppMatcher.capitalsOf(label),
        )

    private fun rank(query: String, labels: List<String>): List<String> =
        labels.mapNotNull { label -> score(query, label)?.let { label to it } }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun matches(query: String, label: String) = score(query, label) != null

    // --- initials ------------------------------------------------------------------------------

    @Test
    fun `word initials come from token starts`() {
        assertEquals("bb", AppMatcher.wordInitialsOf("Big Basket"))
        assertEquals("b", AppMatcher.wordInitialsOf("BookMyShow"))
        assertEquals("gp", AppMatcher.wordInitialsOf("Google Pay"))
    }

    @Test
    fun `capitals come from camel case`() {
        assertEquals("bms", AppMatcher.capitalsOf("BookMyShow"))
        assertEquals("bb", AppMatcher.capitalsOf("Big Basket"))
        assertEquals("wa", AppMatcher.capitalsOf("WhatsApp"))
    }

    @Test
    fun `capitals include a lowercase leading character`() {
        assertEquals("it", AppMatcher.capitalsOf("iTunes"))
    }

    @Test
    fun `initials ignore punctuation`() {
        assertEquals("jd", AppMatcher.wordInitialsOf("Jio-Dhan"))
    }

    // --- the cases from the brief --------------------------------------------------------------

    @Test
    fun `bb finds Big Basket`() {
        assertTrue(matches("bb", "Big Basket"))
    }

    @Test
    fun `bms finds BookMyShow`() {
        assertTrue(matches("bms", "BookMyShow"))
    }

    // --- tier ordering -------------------------------------------------------------------------

    @Test
    fun `exact label beats every other tier`() {
        val ranked = rank("maps", listOf("Maps Go", "Mapsly", "Maps"))
        assertEquals("Maps", ranked.first())
    }

    @Test
    fun `exact word initials beat a label prefix`() {
        // The deliberate judgement call: "bb" is exactly Big Basket's initials.
        val ranked = rank("bb", listOf("BBC News", "Big Basket"))
        assertEquals("Big Basket", ranked.first())
    }

    @Test
    fun `exact capitals beat a prefix`() {
        val ranked = rank("bms", listOf("Bmsomething", "BookMyShow"))
        assertEquals("BookMyShow", ranked.first())
    }

    @Test
    fun `label prefix beats a substring`() {
        val ranked = rank("tube", listOf("YouTube", "Tubemate"))
        assertEquals("Tubemate", ranked.first())
    }

    @Test
    fun `substring beats a loose subsequence`() {
        val ranked = rank("tube", listOf("The UltimateБолт Explorer", "YouTube"))
        assertEquals("YouTube", ranked.first())
    }

    @Test
    fun `word boundary beats a mid-word substring`() {
        val ranked = rank("my", listOf("Enemy Lines", "Book My Show"))
        assertEquals("Book My Show", ranked.first())
    }

    @Test
    fun `shorter label wins within a tier`() {
        val ranked = rank("map", listOf("Maps Go Navigation", "Maps"))
        assertEquals("Maps", ranked.first())
    }

    // --- fuzzy ---------------------------------------------------------------------------------

    @Test
    fun `subsequence matches with gaps`() {
        assertTrue(matches("bkmy", "BookMyShow"))
    }

    @Test
    fun `case is ignored`() {
        assertTrue(matches("BOOK", "BookMyShow"))
        assertTrue(matches("BMS", "BookMyShow"))
    }

    @Test
    fun `unrelated query does not match`() {
        assertNull(score("zzzz", "BookMyShow"))
    }

    @Test
    fun `empty query matches everything`() {
        assertNotNull(score("", "Anything"))
    }
}
