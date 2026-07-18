package com.vynex.gumshoos

/**
 * Query matching for app labels.
 *
 * Tiers, strongest first. Recency breaks ties between equal tiers — see [AppRepository.search] —
 * so among equally good textual matches you get the apps you actually use.
 *
 *   1  exact label                "spotify" -> Spotify
 *   2  exact word initials        "bb"      -> Big Basket
 *   3  exact capitals             "bms"     -> BookMyShow
 *   4  label prefix               "spo"     -> Spotify
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

    /** Which characters a match covers, so the UI can embolden exactly those. */
    data class Match(val score: Int, val indices: List<Int>)

    /**
     * Positions in the label of each token's first character.
     *
     * The strings are derived from these rather than built separately, so a highlight can never
     * disagree with the match that produced it.
     */
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

    private fun charsAt(label: String, indices: List<Int>) =
        indices.map { label[it].lowercaseChar() }.joinToString("")

    /**
     * First character of each whitespace- or punctuation-separated token.
     *
     *   "Big Basket" -> "bb"
     *   "BookMyShow" -> "b"      (one token)
     */
    fun wordInitialsOf(label: String): String = charsAt(label, wordInitialIndices(label))

    /**
     * First character plus every later uppercase one, which is what camel case reads as.
     *
     *   "BookMyShow" -> "bms"
     *   "Big Basket" -> "bb"
     *   "iTunes"     -> "it"     (first char included, so lowercase-leading names still work)
     */
    fun capitalsOf(label: String): String = charsAt(label, capitalIndices(label))

    /** Index of every token start, so a mid-label word match outranks a mid-word one. */
    private fun tokenStarts(label: String): List<Int> {
        val starts = mutableListOf<Int>()
        var atTokenStart = true
        label.forEachIndexed { i, ch ->
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
    fun score(query: String, label: String, wordInitials: String, capitals: String): Int? =
        match(query, label, wordInitials, capitals)?.score

    /**
     * Score plus the label positions the match covers. Both come from one pass so the emboldened
     * characters are always the ones the winning tier actually matched on.
     */
    fun match(query: String, label: String, wordInitials: String, capitals: String): Match? {
        if (query.isEmpty()) return Match(0, emptyList())
        val q = query.lowercase()
        val l = label.lowercase()

        // Shorter labels win within a tier: "Maps" should beat "Maps Go" for "map".
        val brevity = (60 - label.length).coerceAtLeast(0)

        if (l == q) return Match(EXACT_LABEL + brevity, label.indices.toList())
        if (wordInitials == q) {
            return Match(EXACT_WORD_INITIALS + brevity, wordInitialIndices(label))
        }
        if (capitals == q) return Match(EXACT_CAPITALS + brevity, capitalIndices(label))
        if (l.startsWith(q)) return Match(PREFIX_LABEL + brevity, q.indices.toList())
        if (wordInitials.startsWith(q)) {
            return Match(PREFIX_INITIALS + brevity, wordInitialIndices(label).take(q.length))
        }
        if (capitals.startsWith(q)) {
            return Match(PREFIX_INITIALS + brevity, capitalIndices(label).take(q.length))
        }
        tokenStarts(label).firstOrNull { l.startsWith(q, it) }?.let { start ->
            return Match(WORD_BOUNDARY + brevity, (start until start + q.length).toList())
        }
        val at = l.indexOf(q)
        if (at >= 0) return Match(SUBSTRING + brevity, (at until at + q.length).toList())

        return subsequenceMatch(q, l)?.let { (bonus, indices) -> Match(FUZZY + bonus, indices) }
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
