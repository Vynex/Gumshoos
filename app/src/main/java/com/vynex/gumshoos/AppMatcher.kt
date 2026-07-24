package com.vynex.gumshoos

import java.text.Normalizer

/**
 * Query matching for app labels.
 *
 * Matching is accent-insensitive: "poke" finds "Pokémon". Both the query and the label are folded
 * to a plain-ASCII-ish lowercase form first, and the fold carries an index map back to the original
 * label so highlighting still lands on the right characters.
 *
 * Tiers, strongest first. Recency breaks ties between equal tiers — see [AppRepository.search] —
 * so among equally good textual matches you get the apps you actually use.
 *
 *   1  exact label                "spotify" -> Spotify
 *   2  exact word initials        "bb"      -> Big Basket
 *   3  exact capitals             "bms"     -> BookMyShow
 *   4  label prefix               "poke"    -> Pokémon
 *   5  initials prefix            "bm"      -> BookMyShow
 *   6  word-boundary substring    "my"      -> Book*My*Show
 *   7  substring anywhere         "tube"    -> YouTube
 *   8  fuzzy subsequence          "bkmy"    -> BookMyShow
 *   9  typo (prefix)              "gmial"   -> Gmail
 *
 * Tier 8 requires a *real gap*: a query that occurs as a contiguous run is tier 6/7's, never tier
 * 8's, so fuzzy is not a superset of substring. This is what lets the two toggles mean different
 * things — with "anywhere" off, "tube" no longer finds YouTube even while "skipped letters" is on.
 *
 * Tier 9 is the weakest and last resort: it tolerates a bounded edit distance (substitution,
 * insertion, deletion, adjacent transposition) between the query and a *prefix* of the label, so a
 * typo in the first characters is caught as you type. It only runs when every stronger tier misses,
 * so a typo guess never outranks a real match. Fuzzy handles dropped letters; typo is what adds the
 * slips fuzzy cannot see — a wrong letter ("netflox") or two swapped ("gmial").
 *
 * Tiers 2-3 and 5-9 can be switched off — see [MatchTier] — and a label can carry aliases, which
 * match alongside it. Both are settings; both are pure logic and tested here rather than on-device.
 */
object AppMatcher {

    // Spaced widely so a weaker tier can never overtake a stronger one on the brevity bonus.
    private const val TIER = 1_000
    private const val EXACT_LABEL = 90 * TIER
    private const val EXACT_WORD_INITIALS = 80 * TIER
    private const val EXACT_CAPITALS = 70 * TIER
    private const val PREFIX_LABEL = 60 * TIER
    private const val PREFIX_INITIALS = 50 * TIER
    private const val WORD_BOUNDARY = 40 * TIER
    private const val SUBSTRING = 30 * TIER
    private const val FUZZY = 20 * TIER
    private const val TYPO = 10 * TIER

    /** Below this length a single edit is too loose — "cat" would reach every "Car…". */
    private const val MIN_TYPO_LENGTH = 4

    /** Unreachable-cell sentinel for the banded typo DP; halved so `+ 1` cannot overflow. */
    private const val INF = Int.MAX_VALUE / 2

    /**
     * Letters NFD leaves alone because they aren't a base plus a combining mark — they're distinct
     * characters. Stripping marks does nothing for them, so they need spelling out.
     */
    private val UNDECOMPOSABLE = mapOf(
        'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'đ' to "d",
        'ð' to "d", 'ł' to "l", 'þ' to "th", 'ħ' to "h", 'ı' to "i",
    )

    /**
     * Which characters a match covers, as indices into the original label.
     *
     * [indices] is empty when the match came from an alias: the label contains none of the
     * characters the query hit, so emboldening any of them would be a lie.
     */
    data class Match(
        val score: Int,
        val indices: List<Int>,
        val viaAlias: Boolean = false,
    )

    /**
     * A tier that can be switched off.
     *
     * The strong tiers — exact label and label prefix — are not here. Typing a name and having the
     * app that is called that not come up is not a preference anyone holds, and a search with them
     * off is not a search.
     *
     * Word-boundary and interior substrings are one switch because they are one behaviour: turning
     * off "anywhere" while leaving the boundary case on would still find *Book*My*Show* for "my",
     * which is precisely what someone switching it off is trying to stop.
     */
    enum class MatchTier(val id: String, val label: String, val example: String) {
        INITIALS("initials", "Initials", "\"bb\" finds Big Basket"),
        CAPITALS("capitals", "Camel case", "\"bms\" finds BookMyShow"),
        SUBSTRING("substring", "Anywhere in the name", "\"tube\" finds YouTube"),
        FUZZY("fuzzy", "Skipped letters", "\"bkmy\" finds BookMyShow"),
        TYPO("typo", "Typos", "\"gmial\" finds Gmail"),
    }

    /**
     * Which optional tiers are live.
     *
     * A disabled tier is skipped, not scored to zero. Zeroing keeps the match at that tier and its
     * result would still be returned ahead of a weaker tier that is enabled — "bms" would go on
     * finding BookMyShow with camel case off. Skipping lets the query fall through to whatever
     * weaker tier still accepts it, or to no match at all.
     */
    @JvmInline
    value class MatchOptions(val enabled: Set<MatchTier>) {
        operator fun contains(tier: MatchTier): Boolean = tier in enabled

        companion object {
            /** Everything on, which is how matching behaved before any of this was configurable. */
            val ALL = MatchOptions(MatchTier.entries.toSet())
        }
    }

    /**
     * A label prepared for matching: the folded text, plus everything needed to map a hit in that
     * text back to positions in the original.
     *
     * Precomputed once at index time — folding on every keystroke would put Normalizer in the
     * middle of the type-ahead path for every installed app.
     */
    class Indexed(
        val original: String,
        val folded: String,
        /** folded[i] came from original[foldedToSource[i]]. */
        private val foldedToSource: IntArray,
        val wordInitials: String,
        private val wordInitialSources: IntArray,
        val capitals: String,
        private val capitalSources: IntArray,
    ) {
        fun sourcesForFolded(range: IntRange): List<Int> =
            range.mapNotNull { foldedToSource.getOrNull(it) }.distinct()

        fun sourcesForWordInitials(count: Int): List<Int> =
            wordInitialSources.take(count)

        fun sourcesForCapitals(count: Int): List<Int> =
            capitalSources.take(count)
    }

    /** Lowercase, strip diacritics, expand the letters NFD can't split. */
    fun foldQuery(text: String): String {
        val out = StringBuilder()
        for (ch in text) out.append(foldChar(ch))
        return out.toString()
    }

    private fun foldChar(ch: Char): String {
        val lower = ch.lowercaseChar()
        UNDECOMPOSABLE[lower]?.let { return it }
        val decomposed = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
        val stripped = decomposed.filter {
            Character.getType(it) != Character.NON_SPACING_MARK.toInt()
        }
        return stripped.ifEmpty { lower.toString() }
    }

    fun index(label: String): Indexed {
        val folded = StringBuilder()
        val sources = mutableListOf<Int>()
        label.forEachIndexed { i, ch ->
            // One source character can fold to several (ß -> ss), so every produced character
            // records where it came from; that keeps highlighting aligned.
            foldChar(ch).forEach { folded.append(it); sources.add(i) }
        }

        val wordInitialIdx = wordInitialIndices(label)
        val capitalIdx = capitalIndices(label)
        return Indexed(
            original = label,
            folded = folded.toString(),
            foldedToSource = sources.toIntArray(),
            wordInitials = wordInitialIdx.joinToString("") { foldChar(label[it]) },
            wordInitialSources = wordInitialIdx.toIntArray(),
            capitals = capitalIdx.joinToString("") { foldChar(label[it]) },
            capitalSources = capitalIdx.toIntArray(),
        )
    }

    /**
     * Aliases as they are typed in settings — one line's worth, comma separated — each indexed the
     * same way a label is, so an alias matches through every tier a real name does.
     *
     * Blank entries are dropped rather than indexed: an empty alias folds to an empty string, which
     * every label starts with, so it would match everything.
     */
    fun indexAliases(text: String): List<Indexed> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { index(it) }

    /** Positions in the label of each token's first character. */
    fun wordInitialIndices(label: String): List<Int> {
        val out = mutableListOf<Int>()
        var atTokenStart = true
        label.forEachIndexed { i, ch ->
            when {
                !ch.isLetterOrDigit() -> atTokenStart = true
                atTokenStart -> {
                    out.add(i)
                    atTokenStart = false
                }
            }
        }
        return out
    }

    /** Position 0 plus every later uppercase character or digit. */
    fun capitalIndices(label: String): List<Int> {
        if (label.isEmpty()) return emptyList()
        val out = mutableListOf(0)
        for (i in 1 until label.length) {
            val ch = label[i]
            if (ch.isUpperCase() || ch.isDigit()) out.add(i)
        }
        return out
    }

    /** Index of every token start in the folded text. */
    private fun tokenStarts(text: String): List<Int> {
        val starts = mutableListOf<Int>()
        var atTokenStart = true
        text.forEachIndexed { i, ch ->
            if (!ch.isLetterOrDigit()) {
                atTokenStart = true
            } else {
                if (atTokenStart) starts.add(i)
                atTokenStart = false
            }
        }
        return starts
    }

    /** Higher is better; null means no match at all. */
    fun score(
        query: String,
        indexed: Indexed,
        options: MatchOptions = MatchOptions.ALL,
    ): Int? = match(query, indexed, options)?.score

    /**
     * The best match across a label and its aliases.
     *
     * An alias wins only on a strictly stronger tier, not on score. Score carries the brevity
     * bonus, and an alias is almost always shorter than the name it stands in for — comparing on it
     * would let "insta" outscore Instagram for "inst" at the very same tier, costing the highlight
     * for no gain in rank. So the label keeps a tie, and an alias only ever promotes an app that
     * matched weakly or not at all.
     */
    fun matchBest(
        query: String,
        indexed: Indexed,
        aliases: List<Indexed>,
        options: MatchOptions = MatchOptions.ALL,
    ): Match? {
        var best = match(query, indexed, options)
        for (alias in aliases) {
            val hit = match(query, alias, options) ?: continue
            if (best == null || tierOf(hit.score) > tierOf(best.score)) {
                // Indices are dropped: they point into the alias, and the cell draws the label.
                best = Match(hit.score, emptyList(), viaAlias = true)
            }
        }
        return best
    }

    /** The tier a score came from, with the brevity and gap bonuses — both under [TIER] — cut off. */
    private fun tierOf(score: Int): Int = score / TIER

    /**
     * Score plus the label positions the match covers. Both come from one pass so the emboldened
     * characters are always the ones the winning tier actually matched on.
     */
    fun match(
        query: String,
        indexed: Indexed,
        options: MatchOptions = MatchOptions.ALL,
    ): Match? {
        if (query.isEmpty()) return Match(0, emptyList())
        val q = foldQuery(query)
        if (q.isEmpty()) return Match(0, emptyList())
        val l = indexed.folded

        // Shorter labels win within a tier: "Maps" should beat "Maps Go" for "map".
        val brevity = (60 - indexed.original.length).coerceAtLeast(0)

        val initials = MatchTier.INITIALS in options
        val capitals = MatchTier.CAPITALS in options

        if (l == q) return Match(EXACT_LABEL + brevity, indexed.original.indices.toList())
        if (initials && indexed.wordInitials == q) {
            return Match(EXACT_WORD_INITIALS + brevity, indexed.sourcesForWordInitials(q.length))
        }
        if (capitals && indexed.capitals == q) {
            return Match(EXACT_CAPITALS + brevity, indexed.sourcesForCapitals(q.length))
        }
        if (l.startsWith(q)) {
            return Match(PREFIX_LABEL + brevity, indexed.sourcesForFolded(0 until q.length))
        }
        if (initials && indexed.wordInitials.startsWith(q)) {
            return Match(PREFIX_INITIALS + brevity, indexed.sourcesForWordInitials(q.length))
        }
        if (capitals && indexed.capitals.startsWith(q)) {
            return Match(PREFIX_INITIALS + brevity, indexed.sourcesForCapitals(q.length))
        }
        if (MatchTier.SUBSTRING in options) {
            tokenStarts(l).firstOrNull { l.startsWith(q, it) }?.let { start ->
                return Match(
                    WORD_BOUNDARY + brevity,
                    indexed.sourcesForFolded(start until start + q.length),
                )
            }
            val at = l.indexOf(q)
            if (at >= 0) {
                return Match(SUBSTRING + brevity, indexed.sourcesForFolded(at until at + q.length))
            }
        }
        // Fuzzy is "skipped letters": the match must have a real gap. A query that occurs as a
        // contiguous run is a substring, which is a stronger tier's job — so it is rejected here
        // even when SUBSTRING is off. Otherwise fuzzy, a superset of substring, would re-admit every
        // contiguous query the substring toggle just removed: turning off "Anywhere in the name"
        // would do nothing for "tube" -> YouTube, since "tube" is also a gap-free subsequence of it.
        // When SUBSTRING is on we already returned above on any contiguous hit, so this only bites
        // with it off. `contains` rejects *every* contiguous occurrence, not just the greedy one
        // subsequenceMatch would find, so "a real gap" holds however the letters line up.
        if (MatchTier.FUZZY in options && !l.contains(q)) {
            subsequenceMatch(q, l)?.let { (bonus, hits) ->
                return Match(FUZZY + bonus, hits.flatMap { indexed.sourcesForFolded(it..it) })
            }
        }

        // Typo is the last resort, below fuzzy. Indices are empty: the label holds the *corrected*
        // characters, not the ones typed, so there is nothing honest to embolden — same as an alias.
        if (MatchTier.TYPO in options) {
            typoBonus(q, l, brevity)?.let { return Match(TYPO + it, emptyList()) }
        }
        return null
    }

    /**
     * How close the query is to a *prefix* of the label, as an edit distance, or null if it is
     * further than a small budget — so a typo in the characters typed so far still matches.
     *
     * The distance is prefix-aware Damerau/OSA: the fewest single-character edits — substitute,
     * insert, delete, or swap two adjacent — that turn [q] into some prefix of [l]. A prefix rather
     * than the whole label is what lets it fire mid-word: "spofi" is one edit from "spoti", the start
     * of Spotify, long before the name is finished. Fuzzy already covers pure deletions, so what this
     * adds over it is the substitution and the transposition.
     *
     * Two things keep it off the type-ahead hot path. It is skipped below [MIN_TYPO_LENGTH]. And the
     * DP is banded to the budget: since edit distance is at least the length difference, only cells
     * within [maxEdits] of the diagonal can hold a value that small, so the work is O(len * budget)
     * with three rolling int rows — the third row is what the transposition case reads (i-2). A
     * distance of 0 would mean the query *is* a prefix, which tier 4 already returned, so it can only
     * arrive here ≥ 1; it is treated as no match defensively all the same.
     */
    private fun typoBonus(q: String, l: String, brevity: Int): Int? {
        val m = q.length
        if (m < MIN_TYPO_LENGTH) return null
        val maxEdits = if (m >= 8) 2 else 1

        // dp rows over prefixes of l (columns j). Bounded to length m + maxEdits: a longer prefix is
        // already more than maxEdits of insertions away from a length-m query, so it can't win.
        val maxCol = minOf(l.length, m + maxEdits)
        var prev2 = IntArray(maxCol + 1)          // row i-2
        var prev = IntArray(maxCol + 1) { it }    // row i-1, starts as row 0: j deletions from ""
        var curr = IntArray(maxCol + 1)

        for (i in 1..m) {
            val from = maxOf(1, i - maxEdits)
            val to = minOf(maxCol, i + maxEdits)
            // Cells outside the band are unreachable within budget; INF stops them being read as 0.
            if (from > 1) curr[from - 1] = INF
            curr[0] = i                            // i deletions to reach the empty prefix
            var rowMin = curr[0]
            for (j in from..to) {
                val cost = if (q[i - 1] == l[j - 1]) 0 else 1
                var best = minOf(
                    prev[j] + 1,                   // delete q[i-1]
                    curr[j - 1] + 1,               // insert l[j-1]
                    prev[j - 1] + cost,            // substitute / match
                )
                if (i > 1 && j > 1 && q[i - 1] == l[j - 2] && q[i - 2] == l[j - 1]) {
                    best = minOf(best, prev2[j - 2] + 1)   // swap adjacent
                }
                curr[j] = best
                if (best < rowMin) rowMin = best
            }
            if (to < maxCol) curr[to + 1] = INF
            if (rowMin > maxEdits) return null     // no prefix within budget is still reachable
            val spun = prev2; prev2 = prev; prev = curr; curr = spun
        }

        // Best over the prefixes a length-m query could be within budget of; prev is now row m.
        var best = INF
        for (j in maxOf(0, m - maxEdits)..minOf(maxCol, m + maxEdits)) best = minOf(best, prev[j])
        if (best !in 1..maxEdits) return null
        // Fewer edits rank higher, brevity breaks ties; kept under TIER so every typo stays tier 10.
        return (brevity + (maxEdits - best + 1) * 30).coerceIn(0, TIER - 1)
    }

    /**
     * Characters in order but not adjacent. Penalises gaps so tightly-clustered matches rank above
     * ones scattered across the whole label.
     */
    private fun subsequenceMatch(q: String, l: String): Pair<Int, List<Int>>? {
        var qi = 0
        var lastHit = -1
        var gaps = 0
        val hits = mutableListOf<Int>()
        for (li in l.indices) {
            if (qi >= q.length) break
            if (l[li] == q[qi]) {
                if (lastHit >= 0) gaps += li - lastHit - 1
                lastHit = li
                hits.add(li)
                qi++
            }
        }
        if (qi < q.length) return null
        return (200 - gaps).coerceAtLeast(0) to hits
    }
}
