package com.vynex.gumshoos.answers

/**
 * Conversion between decimal, binary, octal and hexadecimal.
 *
 * Separate from [UnitAnswerProvider] because the parsing has nothing in common: a base is written
 * as a prefix on the number itself (`0x1F`) rather than as a unit beside it.
 *
 * Only a prefixed literal or an explicit target will trigger this. A bare `1010` stays a search —
 * guessing it might be binary would break every numeric app name.
 */
object NumberBaseAnswerProvider : AnswerProvider {

    override val id = "bases"
    override val label = "Number base"

    private enum class Base(val radix: Int, val prefix: String, val names: Set<String>) {
        DECIMAL(10, "", setOf("dec", "decimal", "base10", "10")),
        BINARY(2, "0b", setOf("bin", "binary", "base2", "2")),
        OCTAL(8, "0o", setOf("oct", "octal", "base8", "8")),
        HEX(16, "0x", setOf("hex", "hexadecimal", "base16", "16"));

        fun format(value: Long): String {
            val magnitude = java.lang.Long.toString(Math.abs(value), radix)
                .let { if (radix == 16) it.uppercase() else it }
            val sign = if (value < 0) "-" else ""
            return "$sign$prefix$magnitude"
        }
    }

    /** `0x1F`, `0b1010`, `0o17`, each optionally followed by a target. */
    private val PREFIXED = Regex(
        """^\s*([+-]?)0([xbo])([0-9a-fA-F]+)\s*(?:(?:to|in|as|>|->)\s*([a-zA-Z0-9]+))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** `255 to hex` — plain decimal with an explicit target. */
    private val TO_BASE = Regex(
        """^\s*([+-]?\d[\d,]*)\s*(?:to|in|as|>|->)\s*([a-zA-Z0-9]+)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    override fun answer(query: String, context: AnswerContext): Answer? =
        fromPrefixed(query) ?: fromDecimal(query)

    private fun fromPrefixed(query: String): Answer? {
        val match = PREFIXED.matchEntire(query) ?: return null
        val source = when (match.groupValues[2].lowercase()) {
            "x" -> Base.HEX
            "b" -> Base.BINARY
            "o" -> Base.OCTAL
            else -> return null
        }

        val digits = match.groupValues[3]
        // "0b102" is not binary. Rejecting rather than coercing keeps a typo from becoming a
        // confidently wrong answer.
        val value = runCatching {
            java.lang.Long.parseLong(match.groupValues[1] + digits, source.radix)
        }.getOrNull() ?: return null

        val target = match.groupValues[4].takeIf { it.isNotEmpty() }
            ?.let { baseNamed(it) ?: return null }
            ?: Base.DECIMAL

        return build(value, target)
    }

    private fun fromDecimal(query: String): Answer? {
        val match = TO_BASE.matchEntire(query) ?: return null
        val value = match.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        val target = baseNamed(match.groupValues[2]) ?: return null
        return build(value, target)
    }

    /**
     * The headline is the requested base; the detail carries the other three, since anyone
     * converting bases usually wants to see them together.
     */
    private fun build(value: Long, target: Base): Answer {
        val others = Base.entries
            .filter { it != target }
            .joinToString("   ·   ") { it.format(value) }

        return Answer(
            providerId = id,
            headline = target.format(value),
            detail = others,
            copyText = target.format(value),
        )
    }

    private fun baseNamed(token: String): Base? {
        val normalised = token.lowercase()
        return Base.entries.firstOrNull { normalised in it.names }
    }
}
