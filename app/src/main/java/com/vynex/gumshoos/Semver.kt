package com.vynex.gumshoos

/**
 * A semantic version, and just enough of the spec to answer the one question asked of it: is the
 * tag on a GitHub release newer than what is installed.
 *
 * Kept as pure JVM logic — no Android types — so the comparison rules are unit-testable, which is
 * where every subtle case (a prerelease being *older* than its own release, numeric identifiers
 * sorting below alphabetic ones) is pinned. [UpdateChecker] does the network and the state; this
 * only parses and compares.
 *
 * Parsing is deliberately lenient about the *shape* and strict about the *result*: a leading `v`,
 * a missing minor or patch, and a `+build` suffix are all accepted, but anything that does not
 * reduce to three integers and a well-formed prerelease returns null rather than a guess. A tag we
 * cannot read is treated as "no update" upstream, the same fail-safe the rest of the app uses on an
 * endpoint that owes us nothing.
 */
data class Semver(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Dot-separated identifiers after a `-`, empty for a normal release. */
    val preRelease: List<String> = emptyList(),
) : Comparable<Semver> {

    /** `1.2.0` or `1.2.0-rc.1`; the `+build` metadata is dropped on parse and never printed. */
    override fun toString(): String {
        val core = "$major.$minor.$patch"
        return if (preRelease.isEmpty()) core else "$core-${preRelease.joinToString(".")}"
    }

    /**
     * Precedence per the semver spec: the numeric core first, then the prerelease rule that a build
     * carrying one ranks *below* the same core without one — `1.2.0-rc.1` is older than `1.2.0`.
     */
    override fun compareTo(other: Semver): Int {
        compareValuesBy(this, other, Semver::major, Semver::minor, Semver::patch)
            .let { if (it != 0) return it }

        // A release with no prerelease outranks one that has any; two plain releases with an equal
        // core are equal.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1

        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            comparePreReleaseIds(preRelease[i], other.preRelease[i])
                .let { if (it != 0) return it }
        }
        // All shared identifiers equal — the longer set has higher precedence (rc.1 < rc.1.1).
        return preRelease.size.compareTo(other.preRelease.size)
    }

    companion object {
        // v?  major  [.minor]  [.patch]  [-prerelease]  [+build]
        private val PATTERN =
            Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?(?:\+[0-9A-Za-z.\-]+)?$""")

        /**
         * The [Semver] a tag names, or null if it is not one we can compare.
         *
         * A missing minor or patch reads as zero, so a `v2` tag is `2.0.0`. Integer overflow on an
         * absurd component and an empty prerelease identifier (`1.0.0-`) both fail rather than being
         * coerced, because a wrong answer here nags the user with a phantom update.
         */
        fun parse(raw: String?): Semver? {
            val match = PATTERN.matchEntire(raw?.trim() ?: return null) ?: return null
            val groups = match.groupValues
            val major = groups[1].toIntOrNull() ?: return null
            val minor = groups[2].toIntOrNull() ?: 0
            val patch = groups[3].toIntOrNull() ?: 0
            val preRelease = groups[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (preRelease.any { it.isEmpty() }) return null
            return Semver(major, minor, patch, preRelease)
        }
    }
}

/** Numeric identifiers compare numerically and rank below alphanumeric ones; the rest compare ASCII. */
private fun comparePreReleaseIds(a: String, b: String): Int {
    val an = a.toIntOrNull()
    val bn = b.toIntOrNull()
    return when {
        an != null && bn != null -> an.compareTo(bn)
        an != null -> -1
        bn != null -> 1
        else -> a.compareTo(b)
    }
}
