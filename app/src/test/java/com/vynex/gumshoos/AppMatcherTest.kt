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

    // --- match toggles -------------------------------------------------------------------------

    private fun options(vararg off: AppMatcher.MatchTier) =
        AppMatcher.MatchOptions(AppMatcher.MatchTier.entries.toSet() - off.toSet())

    private fun score(query: String, label: String, options: AppMatcher.MatchOptions): Int? =
        AppMatcher.score(query, AppMatcher.index(label), options)

    private fun rank(
        query: String,
        labels: List<String>,
        options: AppMatcher.MatchOptions,
    ): List<String> =
        labels.mapNotNull { label -> score(query, label, options)?.let { label to it } }
            .sortedByDescending { it.second }
            .map { it.first }

    @Test
    fun `the always-on tiers survive every toggle being off`() {
        val none = AppMatcher.MatchOptions(emptySet())
        assertNotNull(score("spotify", "Spotify", none))
        assertNotNull(score("spot", "Spotify", none))
    }

    @Test
    fun `disabling fuzzy drops a subsequence-only match`() {
        assertNotNull(score("bkmy", "BookMyShow"))
        assertNull(score("bkmy", "BookMyShow", options(AppMatcher.MatchTier.FUZZY)))
    }

    @Test
    fun `disabling substring drops a mid-label match`() {
        assertNotNull(score("tube", "YouTube"))
        assertNull(
            score("tube", "YouTube", options(AppMatcher.MatchTier.SUBSTRING, AppMatcher.MatchTier.FUZZY)),
        )
    }

    @Test
    fun `disabling substring also drops the word-boundary case`() {
        // The two are one switch: leaving boundary matching on would still find "my" in
        // "Book My Show", which is exactly what switching it off is meant to stop.
        assertNull(
            score(
                "my",
                "Book My Show",
                options(AppMatcher.MatchTier.SUBSTRING, AppMatcher.MatchTier.FUZZY),
            ),
        )
    }

    @Test
    fun `disabling initials drops an initials match`() {
        assertNotNull(score("bb", "Big Basket"))
        assertNull(
            score(
                "bb",
                "Big Basket",
                options(
                    AppMatcher.MatchTier.INITIALS,
                    AppMatcher.MatchTier.CAPITALS,
                    AppMatcher.MatchTier.FUZZY,
                ),
            ),
        )
    }

    @Test
    fun `a disabled tier is skipped, not scored to zero`() {
        // With camel case on, BookMyShow wins on exact capitals. With it off it must fall through
        // to a weaker tier rather than staying ahead — a zeroed tier would still be returned here.
        val off = options(AppMatcher.MatchTier.CAPITALS)
        assertEquals("BookMyShow", rank("bms", listOf("Bmsoft", "BookMyShow")).first())
        assertEquals("Bmsoft", rank("bms", listOf("Bmsoft", "BookMyShow"), off).first())
    }

    @Test
    fun `a query only a disabled tier accepted still matches through a weaker one`() {
        // Skipping is not the same as excluding: capitals off leaves fuzzy to accept "bms".
        assertNotNull(score("bms", "BookMyShow", options(AppMatcher.MatchTier.CAPITALS)))
    }

    // --- aliases -------------------------------------------------------------------------------

    private fun matchWithAliases(
        query: String,
        label: String,
        aliases: String,
        options: AppMatcher.MatchOptions = AppMatcher.MatchOptions.ALL,
    ): AppMatcher.Match? = AppMatcher.matchBest(
        query,
        AppMatcher.index(label),
        AppMatcher.indexAliases(aliases),
        options,
    )

    @Test
    fun `aliases are split on commas and trimmed`() {
        val indexed = AppMatcher.indexAliases(" insta ,ig ")
        assertEquals(listOf("insta", "ig"), indexed.map { it.original })
    }

    @Test
    fun `a blank alias is dropped rather than matching everything`() {
        assertEquals(emptyList<String>(), AppMatcher.indexAliases(" , ,").map { it.original })
        // An empty alias folds to "", which every label starts with.
        assertNull(matchWithAliases("zzz", "Instagram", ",  ,"))
    }

    @Test
    fun `an alias finds an app its label never would`() {
        assertNull(score("ig", "Camera"))
        assertNotNull(matchWithAliases("ig", "Camera", "ig"))
    }

    @Test
    fun `an alias lifts a match out of the fuzzy tier`() {
        // "ig" already reaches Instagram as a subsequence, far down the results. The alias is what
        // puts it at the top.
        val fuzzy = score("ig", "Instagram")!!
        assertTrue(matchWithAliases("ig", "Instagram", "ig")!!.score > fuzzy)
    }

    @Test
    fun `an alias win highlights nothing`() {
        // The label holds none of the characters that were matched, so emboldening any would lie.
        val match = matchWithAliases("ig", "Instagram", "ig")
        assertEquals(emptyList<Int>(), match?.indices)
        assertTrue(match!!.viaAlias)
    }

    @Test
    fun `a label match still highlights when an alias also matches`() {
        // Both hit the label-prefix tier, and the shorter alias scores higher on brevity. Comparing
        // on score rather than tier would hand the win — and the highlight — to the alias.
        val match = matchWithAliases("inst", "Instagram", "insta")
        assertEquals(false, match?.viaAlias)
        assertEquals(listOf(0, 1, 2, 3), match?.indices)
    }

    @Test
    fun `the label wins a tie with an alias`() {
        // Both hit the exact-label tier; the app actually called that must not be demoted.
        val match = matchWithAliases("photos", "Photos", "photos")
        assertEquals(false, match?.viaAlias)
    }

    @Test
    fun `an alias only promotes, never demotes`() {
        val labelOnly = score("insta", "Instagram")!!
        val withAlias = matchWithAliases("insta", "Instagram", "gram")!!.score
        assertTrue(withAlias >= labelOnly)
    }

    @Test
    fun `an alias respects the match toggles`() {
        // The alias is matched through the same tiers as a label, so a disabled one can't sneak
        // back in through it.
        assertNotNull(matchWithAliases("bm", "Instagram", "BookMy"))
        assertNull(
            matchWithAliases(
                "bm",
                "Instagram",
                "BookMy",
                options(
                    AppMatcher.MatchTier.INITIALS,
                    AppMatcher.MatchTier.CAPITALS,
                    AppMatcher.MatchTier.FUZZY,
                ),
            ),
        )
    }

    @Test
    fun `an alias beats a weaker label match on another app`() {
        val instagram = matchWithAliases("ig", "Instagram", "ig")!!.score
        val fuzzy = score("ig", "Imagine Grid")!!
        assertTrue(instagram > fuzzy)
    }
}
