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

    /**
     * First character of each whitespace- or punctuation-separated token.
     *
     *   "Big Basket" -> "bb"
     *   "BookMyShow" -> "b"      (one token)
     */
    fun wordInitialsOf(label: String): String {
        val out = StringBuilder()
        var atTokenStart = true
        for (ch in label) {
            when {
                !ch.isLetterOrDigit() -> atTokenStart = true
                atTokenStart -> {
                    out.append(ch.lowercaseChar())
                    atTokenStart = false
                }
            }
        }
        return out.toString()
    }

    /**
     * First character plus every later uppercase one, which is what camel case reads as.
     *
     *   "BookMyShow" -> "bms"
     *   "Big Basket" -> "bb"
     *   "iTunes"     -> "it"     (first char included, so lowercase-leading names still work)
     */
    fun capitalsOf(label: String): String {
        if (label.isEmpty()) return ""
        val out = StringBuilder()
        out.append(label.first().lowercaseChar())
        for (i in 1 until label.length) {
            val ch = label[i]
            if (ch.isUpperCase() || ch.isDigit()) out.append(ch.lowercaseChar())
        }
        return out.toString()
    }

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
    fun score(query: String, label: String, wordInitials: String, capitals: String): Int? {
        if (query.isEmpty()) return 0
        val q = query.lowercase()
        val l = label.lowercase()

        // Shorter labels win within a tier: "Maps" should beat "Maps Go" for "map".
        val brevity = (60 - label.length).coerceAtLeast(0)

        if (l == q) return EXACT_LABEL + brevity
        if (wordInitials == q) return EXACT_WORD_INITIALS + brevity
        if (capitals == q) return EXACT_CAPITALS + brevity
        if (l.startsWith(q)) return PREFIX_LABEL + brevity
        if (wordInitials.startsWith(q) || capitals.startsWith(q)) return PREFIX_INITIALS + brevity
        if (tokenStarts(label).any { l.startsWith(q, it) }) return WORD_BOUNDARY + brevity
        if (l.contains(q)) return SUBSTRING + brevity

        return subsequenceScore(q, l)?.let { FUZZY + it }
    }

    /**
     * Characters in order but not adjacent. Penalises gaps so tightly-clustered matches rank above
     * ones scattered across the whole label.
     */
    private fun subsequenceScore(q: String, l: String): Int? {
        var qi = 0
        var lastHit = -1
        var gaps = 0
        for (li in l.indices) {
            if (qi >= q.length) break
            if (l[li] == q[qi]) {
                if (lastHit >= 0) gaps += li - lastHit - 1
                lastHit = li
                qi++
            }
        }
        if (qi < q.length) return null
        return (200 - gaps).coerceAtLeast(0)
    }
}
