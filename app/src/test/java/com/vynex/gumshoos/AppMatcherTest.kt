package com.vynex.gumshoos

import com.vynex.gumshoos.AppMatcher.MatchTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    //
    // Each optional tier is exercised *in isolation* with `only`, which enables that one tier and
    // nothing else, so a match can have come from no other net. That is the only way to prove a
    // toggle owns the behaviour attributed to it. The earlier suite never did this: every "disabling
    // substring" test switched off FUZZY at the same time, so it could not have caught that substring
    // *alone* changes nothing — which was the reported bug. INITIALS/CAPITALS and SUBSTRING/FUZZY are
    // two pairs of overlapping nets, so a test that flips both members at once cannot tell them apart.

    private fun options(vararg off: MatchTier) =
        AppMatcher.MatchOptions(MatchTier.entries.toSet() - off.toSet())

    /** Only these optional tiers live; the always-on tiers (exact label, label prefix) stay on. */
    private fun only(vararg on: MatchTier) = AppMatcher.MatchOptions(on.toSet())

    private fun score(query: String, label: String, options: AppMatcher.MatchOptions): Int? =
        AppMatcher.score(query, AppMatcher.index(label), options)

    private fun matches(query: String, label: String, options: AppMatcher.MatchOptions) =
        score(query, label, options) != null

    private fun rank(
        query: String,
        labels: List<String>,
        options: AppMatcher.MatchOptions,
    ): List<String> =
        labels.mapNotNull { label -> score(query, label, options)?.let { label to it } }
            .sortedByDescending { it.second }
            .map { it.first }

    // --- the always-on tiers are never touched by a toggle -------------------------------------

    @Test
    fun `the always-on tiers survive every toggle being off`() {
        val none = AppMatcher.MatchOptions(emptySet())
        assertNotNull(score("spotify", "Spotify", none))
        assertNotNull(score("spot", "Spotify", none))
    }

    @Test
    fun `exact label and label prefix survive any single toggle`() {
        for (tier in MatchTier.entries) {
            assertTrue("label prefix broke with $tier off", matches("you", "YouTube", options(tier)))
            assertTrue("exact label broke with $tier off", matches("youtube", "YouTube", options(tier)))
        }
    }

    // --- each optional tier, owned and isolated ------------------------------------------------

    @Test
    fun `initials tier owns an initials-only match`() {
        // "is" is the word-initials of "iTunes Store". Its capitals are "its", so capitals cannot
        // accept "is", and "is" is neither the exact label nor a label prefix — only initials can.
        assertTrue(matches("is", "iTunes Store", only(MatchTier.INITIALS)))
        assertFalse(matches("is", "iTunes Store", only(MatchTier.CAPITALS)))
        assertFalse(matches("is", "iTunes Store", only(MatchTier.SUBSTRING)))
    }

    @Test
    fun `capitals tier owns a camel-case-only match`() {
        // BookMyShow is one token, so its word-initials are just "b"; only the capitals tier reads
        // "bms" out of it, and "bms" is neither exact label nor prefix nor a contiguous substring.
        assertTrue(matches("bms", "BookMyShow", only(MatchTier.CAPITALS)))
        assertFalse(matches("bms", "BookMyShow", only(MatchTier.INITIALS)))
        assertFalse(matches("bms", "BookMyShow", only(MatchTier.SUBSTRING)))
    }

    @Test
    fun `substring tier owns a mid-label match`() {
        assertTrue(matches("tube", "YouTube", only(MatchTier.SUBSTRING)))
        assertFalse(matches("tube", "YouTube", only(MatchTier.INITIALS)))
        assertFalse(matches("tube", "YouTube", only(MatchTier.CAPITALS)))
        // The headline fix: fuzzy must NOT stand in for substring on a contiguous query.
        assertFalse(matches("tube", "YouTube", only(MatchTier.FUZZY)))
    }

    @Test
    fun `substring tier owns the word-boundary case`() {
        assertTrue(matches("my", "Book My Show", only(MatchTier.SUBSTRING)))
        assertFalse(matches("my", "Book My Show", only(MatchTier.FUZZY)))
    }

    @Test
    fun `fuzzy tier owns a skipped-letter match`() {
        // Scattered and non-contiguous, so substring cannot see it and only fuzzy can.
        assertTrue(matches("bkmy", "BookMyShow", only(MatchTier.FUZZY)))
        assertFalse(matches("bkmy", "BookMyShow", only(MatchTier.SUBSTRING)))
    }

    @Test
    fun `fuzzy requires a real gap, not a contiguous run`() {
        // "ytube" skips "ou" — a genuine gap — so fuzzy keeps it.
        assertTrue(matches("ytube", "YouTube", only(MatchTier.FUZZY)))
        // "tube" is a contiguous run, which belongs to substring; fuzzy rejects it. This is what
        // stops fuzzy being a superset of substring, so the two toggles mean different things.
        assertFalse(matches("tube", "YouTube", only(MatchTier.FUZZY)))
        assertFalse(matches("my", "Book My Show", only(MatchTier.FUZZY)))
    }

    @Test
    fun `fuzzy rejects a contiguous run wherever it sits, not only under the greedy scan`() {
        // "ab" occurs both scattered (a…b) and contiguous in "aXab". `contains` sees the contiguous
        // occurrence and rejects, so "a real gap" holds however the letters happen to line up — a
        // check that only inspected the greedy subsequence would let this leak through.
        assertFalse(matches("ab", "aXab", only(MatchTier.FUZZY)))
    }

    // --- the reported bug, and the pairs that need both members off ----------------------------

    @Test
    fun `turning off only Anywhere in the name stops a contiguous query`() {
        // The exact report: "tube" still found YouTube with "Anywhere in the name" off, because
        // FUZZY re-admitted it. It no longer does — INITIALS and CAPITALS stay on (their defaults),
        // FUZZY is on, and only SUBSTRING is flipped, exactly as the settings switch does.
        assertNotNull(score("tube", "YouTube"))
        assertNull(score("tube", "YouTube", options(MatchTier.SUBSTRING)))
        assertNull(score("my", "Book My Show", options(MatchTier.SUBSTRING)))
    }

    @Test
    fun `the toggles are independent`() {
        // Turning off one optional tier leaves each of the others doing its own job.
        assertTrue(matches("bms", "BookMyShow", options(MatchTier.INITIALS)))    // capitals still on
        assertTrue(matches("is", "iTunes Store", options(MatchTier.CAPITALS)))   // initials still on
        assertTrue(matches("tube", "YouTube", options(MatchTier.FUZZY)))         // substring still on
        assertTrue(matches("bkmy", "BookMyShow", options(MatchTier.SUBSTRING)))  // fuzzy still on
    }

    @Test
    fun `a query several tiers accept needs all of them off`() {
        // "bb" is Big Basket's initials AND its capitals, so no single toggle can remove it; with
        // those two and fuzzy off it finally falls through to nothing.
        assertNotNull(score("bb", "Big Basket", options(MatchTier.INITIALS)))
        assertNull(
            score(
                "bb",
                "Big Basket",
                options(MatchTier.INITIALS, MatchTier.CAPITALS, MatchTier.FUZZY),
            ),
        )
    }

    @Test
    fun `disabling fuzzy drops a subsequence-only match`() {
        assertNotNull(score("bkmy", "BookMyShow"))
        assertNull(score("bkmy", "BookMyShow", options(MatchTier.FUZZY)))
    }

    // --- skipping a tier is not the same as excluding a query ----------------------------------

    @Test
    fun `a disabled tier is skipped, not scored to zero`() {
        // With camel case on, BookMyShow wins on exact capitals. With it off it must fall through
        // to a weaker tier rather than staying ahead — a zeroed tier would still be returned here.
        val off = options(MatchTier.CAPITALS)
        assertEquals("BookMyShow", rank("bms", listOf("Bmsoft", "BookMyShow")).first())
        assertEquals("Bmsoft", rank("bms", listOf("Bmsoft", "BookMyShow"), off).first())
    }

    @Test
    fun `a query only a disabled tier accepted still matches through a weaker one`() {
        // Skipping is not excluding: capitals off leaves fuzzy to accept "bms" as a scattered match.
        assertNotNull(score("bms", "BookMyShow", options(MatchTier.CAPITALS)))
    }

    // --- typos (the weakest tier: an edit or two against a prefix) ------------------------------

    @Test
    fun `typo tier catches a transposition fuzzy cannot`() {
        // Two swapped letters are one edit for typo, but not a subsequence — "gmial" needs an "a"
        // before its "i", which "gmail" doesn't offer in that order. So only the typo tier sees it.
        assertTrue(matches("gmial", "Gmail", only(MatchTier.TYPO)))
        assertFalse(matches("gmial", "Gmail", only(MatchTier.FUZZY)))
    }

    @Test
    fun `typo tier catches a substitution fuzzy cannot`() {
        // A wrong letter is one edit; fuzzy can only drop letters, never swap one for another.
        assertTrue(matches("netflox", "Netflix", only(MatchTier.TYPO)))
        assertFalse(matches("netflox", "Netflix", only(MatchTier.FUZZY)))
    }

    @Test
    fun `typo fires on a prefix, before the whole name is typed`() {
        // "spofi" is one substitution from "spoti", the start of Spotify — caught mid-word. This is
        // the prefix-aware behaviour a whole-name comparison would miss, "spofi" being far too short
        // to be one edit from the full "spotify".
        assertTrue(matches("spofi", "Spotify", only(MatchTier.TYPO)))
    }

    @Test
    fun `typo needs at least four characters`() {
        // A single edit on a three-letter query reaches far too much — "cat" would find every "Car…".
        assertFalse(matches("cat", "Car", only(MatchTier.TYPO)))
        assertFalse(matches("abc", "Abd", only(MatchTier.TYPO)))
    }

    @Test
    fun `typo respects the edit budget`() {
        // "spofy" is two edits from any prefix of Spotify (f->t and y->i); the budget for five
        // characters is one, so it stays out.
        assertFalse(matches("spofy", "Spotify", only(MatchTier.TYPO)))
    }

    @Test
    fun `a longer query is allowed two edits`() {
        // Eight characters or more get a budget of two, so a double slip on a long name still lands.
        assertTrue(matches("instagrom", "Instagram", only(MatchTier.TYPO)))   // one substitution
        assertTrue(matches("intsagrom", "Instagram", only(MatchTier.TYPO)))   // transpose + sub
    }

    @Test
    fun `typo ranks below fuzzy`() {
        // A typo match must never be handed back above a genuine skipped-letter match on another app.
        assertTrue(score("gmial", "Gmail")!! < score("bkmy", "BookMyShow")!!)
    }

    @Test
    fun `disabling typo drops a typo-only match`() {
        assertNotNull(score("gmial", "Gmail"))
        assertNull(score("gmial", "Gmail", options(MatchTier.TYPO)))
    }

    @Test
    fun `typo is on by default`() {
        // The always-on-ness is what makes it reach a fresh install without a settings visit.
        assertTrue(MatchTier.TYPO in AppMatcher.MatchOptions.ALL)
    }

    @Test
    fun `typo does not reopen the substring hole`() {
        // With "Anywhere in the name" off, "tube" must still not find YouTube. Typo is on by default
        // now, but "tube" is not within an edit of any prefix of "youtube", so it stays shut.
        assertNull(score("tube", "YouTube", options(MatchTier.SUBSTRING)))
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
