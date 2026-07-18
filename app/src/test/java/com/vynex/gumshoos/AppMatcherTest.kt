package com.vynex.gumshoos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {

    private fun score(query: String, label: String): Int? =
        AppMatcher.score(query, AppMatcher.index(label))

    private fun rank(query: String, labels: List<String>): List<String> =
        labels.mapNotNull { label -> score(query, label)?.let { label to it } }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun matches(query: String, label: String) = score(query, label) != null

    private fun highlighted(query: String, label: String): String {
        val indices = AppMatcher.match(query, AppMatcher.index(label))?.indices.orEmpty().toSet()
        return label.filterIndexed { i, _ -> i in indices }
    }

    // --- initials ------------------------------------------------------------------------------

    @Test
    fun `word initials come from token starts`() {
        assertEquals("bb", AppMatcher.index("Big Basket").wordInitials)
        assertEquals("b", AppMatcher.index("BookMyShow").wordInitials)
        assertEquals("gp", AppMatcher.index("Google Pay").wordInitials)
    }

    @Test
    fun `capitals come from camel case`() {
        assertEquals("bms", AppMatcher.index("BookMyShow").capitals)
        assertEquals("bb", AppMatcher.index("Big Basket").capitals)
        assertEquals("wa", AppMatcher.index("WhatsApp").capitals)
    }

    @Test
    fun `capitals include a lowercase leading character`() {
        assertEquals("it", AppMatcher.index("iTunes").capitals)
    }

    // --- accents -------------------------------------------------------------------------------

    @Test
    fun `accented characters fold to plain ones`() {
        assertEquals("pokemon", AppMatcher.index("Pokémon").folded)
    }

    @Test
    fun `poke finds Pokemon`() {
        assertTrue(matches("poke", "Pokémon"))
    }

    @Test
    fun `an accented query finds the plain label and the reverse`() {
        assertTrue(matches("pokémon", "Pokemon"))
        assertTrue(matches("pokemon", "Pokémon"))
    }

    @Test
    fun `letters NFD cannot split are still folded`() {
        assertEquals("stroget", AppMatcher.index("Strøget").folded)
        assertEquals("strasse", AppMatcher.index("Straße").folded)
    }

    @Test
    fun `accented initials fold too`() {
        assertTrue(matches("ec", "Éclair Café"))
    }

    // --- highlighting stays aligned with the original label -------------------------------------

    @Test
    fun `highlight covers the accented character itself`() {
        // The é must light up, not the character after it — folding must not shift the indices.
        assertEquals("Poké", highlighted("poke", "Pokémon"))
    }

    @Test
    fun `highlight survives a character that folds to two`() {
        // ß folds to "ss", so the folded text is longer than the label; indices must still map back.
        assertEquals("Straß", highlighted("strass", "Straße"))
    }

    @Test
    fun `highlight covers the initials on an initials match`() {
        assertEquals("BMS", highlighted("bms", "BookMyShow"))
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
        assertEquals("Maps", rank("maps", listOf("Maps Go", "Mapsly", "Maps")).first())
    }

    @Test
    fun `exact word initials beat a label prefix`() {
        assertEquals("Big Basket", rank("bb", listOf("BBC News", "Big Basket")).first())
    }

    @Test
    fun `exact capitals beat a prefix`() {
        assertEquals("BookMyShow", rank("bms", listOf("Bmsomething", "BookMyShow")).first())
    }

    @Test
    fun `label prefix beats a substring`() {
        assertEquals("Tubemate", rank("tube", listOf("YouTube", "Tubemate")).first())
    }

    @Test
    fun `word boundary beats a mid-word substring`() {
        assertEquals("Book My Show", rank("my", listOf("Enemy Lines", "Book My Show")).first())
    }

    @Test
    fun `shorter label wins within a tier`() {
        assertEquals("Maps", rank("map", listOf("Maps Go Navigation", "Maps")).first())
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
