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

    /**
     * Letters NFD leaves alone because they aren't a base plus a combining mark — they're distinct
     * characters. Stripping marks does nothing for them, so they need spelling out.
     */
    private val UNDECOMPOSABLE = mapOf(
        'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'đ' to "d",
        'ð' to "d", 'ł' to "l", 'þ' to "th", 'ħ' to "h", 'ı' to "i",
    )

    /** Which characters a match covers, as indices into the original label. */
    data class Match(val score: Int, val indices: List<Int>)

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
    fun score(query: String, indexed: Indexed): Int? = match(query, indexed)?.score

    /**
     * Score plus the label positions the match covers. Both come from one pass so the emboldened
     * characters are always the ones the winning tier actually matched on.
     */
    fun match(query: String, indexed: Indexed): Match? {
        if (query.isEmpty()) return Match(0, emptyList())
        val q = foldQuery(query)
        if (q.isEmpty()) return Match(0, emptyList())
        val l = indexed.folded

        // Shorter labels win within a tier: "Maps" should beat "Maps Go" for "map".
        val brevity = (60 - indexed.original.length).coerceAtLeast(0)

        if (l == q) return Match(EXACT_LABEL + brevity, indexed.original.indices.toList())
        if (indexed.wordInitials == q) {
            return Match(EXACT_WORD_INITIALS + brevity, indexed.sourcesForWordInitials(q.length))
        }
        if (indexed.capitals == q) {
            return Match(EXACT_CAPITALS + brevity, indexed.sourcesForCapitals(q.length))
        }
        if (l.startsWith(q)) {
            return Match(PREFIX_LABEL + brevity, indexed.sourcesForFolded(0 until q.length))
        }
        if (indexed.wordInitials.startsWith(q)) {
            return Match(PREFIX_INITIALS + brevity, indexed.sourcesForWordInitials(q.length))
        }
        if (indexed.capitals.startsWith(q)) {
            return Match(PREFIX_INITIALS + brevity, indexed.sourcesForCapitals(q.length))
        }
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

        return subsequenceMatch(q, l)?.let { (bonus, hits) ->
            Match(FUZZY + bonus, hits.flatMap { indexed.sourcesForFolded(it..it) })
        }
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
