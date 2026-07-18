package com.vynex.gumshoos

/**
 * Query matching for app labels.
 *
 * Handles four shapes of input, in descending confidence:
 *  - prefix / substring of the label            ("book" -> BookMyShow)
 *  - word initials                              ("bb"   -> Big Basket)
 *  - internal capitals                          ("bms"  -> BookMyShow)
 *  - loose subsequence, gap-penalised           ("bkmy" -> BookMyShow)
 */
object AppMatcher {

    /**
     * Condensed initials for a label, covering both spaced words and camel case with one rule:
     * take the first character of every token, plus every subsequent uppercase character.
     *
     *   "Big Basket"  -> "bb"
     *   "BookMyShow"  -> "bms"
     *   "WhatsApp"    -> "wa"
     */
    fun initialsOf(label: String): String {
        val out = StringBuilder()
        var atTokenStart = true
        for (ch in label) {
            when {
                !ch.isLetterOrDigit() -> atTokenStart = true
                atTokenStart -> {
                    out.append(ch.lowercaseChar())
                    atTokenStart = false
                }
                ch.isUpperCase() || ch.isDigit() -> out.append(ch.lowercaseChar())
            }
        }
        return out.toString()
    }

    /** Index of every token start, so a mid-label word match can outrank a mid-word one. */
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

    /**
     * Higher is better; null means no match at all. Tiers are spaced widely enough that a weaker
     * match can never overtake a stronger one on length bonuses alone.
     */
    fun score(query: String, label: String, initials: String): Int? {
        if (query.isEmpty()) return 0
        val q = query.lowercase()
        val l = label.lowercase()

        // Shorter labels win within a tier: "Maps" should beat "Maps Go" for "map".
        val brevity = (60 - label.length).coerceAtLeast(0)

        if (l.startsWith(q)) return 10_000 + brevity
        if (initials.startsWith(q)) return 9_000 + brevity
        if (tokenStarts(label).any { l.startsWith(q, it) }) return 8_000 + brevity
        if (l.contains(q)) return 7_000 + brevity
        if (initials.contains(q)) return 6_000 + brevity

        return subsequenceScore(q, l)?.let { 5_000 + it + brevity / 2 }
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
