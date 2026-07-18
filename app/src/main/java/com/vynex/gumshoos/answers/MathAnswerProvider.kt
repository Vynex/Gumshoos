package com.vynex.gumshoos.answers

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Arithmetic typed straight into the search bar.
 *
 * Hand-written recursive descent rather than a library: the grammar is small, the behaviour is
 * exactly specifiable in tests, and pulling in an expression evaluator for this would be
 * disproportionate.
 *
 * `%` means percent, not modulo — in a launcher "50 + 10%" is far commoner than a remainder.
 * Modulo is available as `mod`.
 */
object MathAnswerProvider : AnswerProvider {

    override val id = "math"
    override val label = "Calculator"

    private val FUNCTIONS = setOf(
        "sqrt", "abs", "round", "floor", "ceil", "sin", "cos", "tan", "log", "ln",
    )

    override fun answer(query: String, context: AnswerContext): Answer? {
        val input = query.removePrefix("=").trim()
        if (input.isEmpty()) return null

        // Needs a digit and some evidence of arithmetic. Without the digit test, "cos" alone would
        // be claimed; without the operator test, every number would be.
        if (input.none { it.isDigit() }) return null
        if (!looksMathematical(input)) return null

        val value = runCatching { Parser(input).evaluate() }.getOrNull() ?: return null
        if (!value.isFinite()) return null

        val formatted = AnswerFormat.number(value, context.locale)
        return Answer(
            providerId = id,
            headline = formatted,
            detail = "${input.trim()} =",
            copyText = formatted,
        )
    }

    private fun looksMathematical(input: String): Boolean {
        if (input.any { it in "+-*/^%()" }) return true
        val lower = input.lowercase()
        if (Regex("""\bmod\b""").containsMatchIn(lower)) return true
        return FUNCTIONS.any { Regex("""\b$it\b""").containsMatchIn(lower) }
    }

    // --- parser ----------------------------------------------------------------------------------

    /**
     * expression := term (('+' | '-') term)*
     * term       := power (('*' | '/' | 'mod') power)*
     * power      := unary ('^' power)?          right-associative
     * unary      := ('-' | '+')? primary
     * primary    := number '%'? | '(' expression ')' | function '(' expression ')' | constant
     */
    private class Parser(private val text: String) {
        private var pos = 0

        fun evaluate(): Double {
            val result = parseExpression()
            skipSpace()
            if (pos < text.length) throw IllegalArgumentException("trailing input at $pos")
            return result
        }

        private fun parseExpression(): Double {
            var left = parseTerm()
            while (true) {
                skipSpace()
                val op = peek() ?: return left
                if (op != '+' && op != '-') return left
                pos++
                val start = pos
                val right = parseTerm()
                // "50 + 10%" means "10% *of 50*", not "plus 0.1". Only a bare trailing percent is
                // treated this way; anything more complex keeps the literal /100 reading.
                left = if (endedWithBarePercent(start)) {
                    if (op == '+') left + left * right else left - left * right
                } else {
                    if (op == '+') left + right else left - right
                }
            }
        }

        /** True when the term just consumed was exactly a number followed by '%'. */
        private fun endedWithBarePercent(start: Int): Boolean {
            val consumed = text.substring(start, pos).trim()
            return Regex("""^\d+(\.\d+)?\s*%$""").matches(consumed)
        }

        private fun parseTerm(): Double {
            var left = parsePower()
            while (true) {
                skipSpace()
                if (matchWord("mod")) {
                    left = left.mod(parsePower())
                    continue
                }
                val op = peek() ?: return left
                when (op) {
                    '*', '×', 'x' -> {
                        // Only treat 'x' as multiplication between numbers, or "2x" would break
                        // every query containing the letter.
                        if (op == 'x' && !isBetweenNumbers()) return left
                        pos++
                        left *= parsePower()
                    }
                    '/', '÷' -> { pos++; left /= parsePower() }
                    else -> return left
                }
            }
        }

        private fun isBetweenNumbers(): Boolean {
            val before = text.getOrNull(pos - 1)
            val after = text.getOrNull(pos + 1)
            return before != null && after != null &&
                (before.isDigit() || before == ' ') && (after.isDigit() || after == ' ')
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            skipSpace()
            if (peek() == '^') {
                pos++
                return base.pow(parsePower())
            }
            return base
        }

        private fun parseUnary(): Double {
            skipSpace()
            return when (peek()) {
                '-' -> { pos++; -parseUnary() }
                '+' -> { pos++; parseUnary() }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            skipSpace()
            val ch = peek() ?: throw IllegalArgumentException("unexpected end")

            if (ch == '(') {
                pos++
                val inner = parseExpression()
                skipSpace()
                if (peek() != ')') throw IllegalArgumentException("unbalanced parenthesis")
                pos++
                return applyTrailingPercent(inner)
            }

            if (ch.isLetter()) return parseWord()

            if (ch.isDigit() || ch == '.') return applyTrailingPercent(parseNumber())

            throw IllegalArgumentException("unexpected '$ch'")
        }

        private fun parseWord(): Double {
            val start = pos
            while (pos < text.length && text[pos].isLetter()) pos++
            val word = text.substring(start, pos).lowercase()

            when (word) {
                "pi" -> return Math.PI
                "e" -> return Math.E
            }
            if (word !in FUNCTIONS) throw IllegalArgumentException("unknown word $word")

            skipSpace()
            // "sqrt 16" is as natural to type as "sqrt(16)", so parentheses are optional.
            val argument = if (peek() == '(') {
                pos++
                val inner = parseExpression()
                skipSpace()
                if (peek() != ')') throw IllegalArgumentException("unbalanced parenthesis")
                pos++
                inner
            } else {
                parseUnary()
            }

            return when (word) {
                "sqrt" -> sqrt(argument)
                "abs" -> abs(argument)
                "round" -> argument.roundToLong().toDouble()
                "floor" -> floor(argument)
                "ceil" -> ceil(argument)
                "sin" -> sin(argument)
                "cos" -> cos(argument)
                "tan" -> tan(argument)
                "log" -> log10(argument)
                "ln" -> ln(argument)
                else -> throw IllegalArgumentException("unknown function $word")
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.' || text[pos] == ',')) {
                pos++
            }
            val raw = text.substring(start, pos).replace(",", "")
            return raw.toDoubleOrNull() ?: throw IllegalArgumentException("bad number $raw")
        }

        /** Handles both "10%" as a value and "10% of 50" as a proportion. */
        private fun applyTrailingPercent(value: Double): Double {
            skipSpace()
            if (peek() != '%') return value
            pos++
            skipSpace()
            if (matchWord("of")) return value / 100.0 * parseExpression()
            return value / 100.0
        }

        private fun matchWord(word: String): Boolean {
            skipSpace()
            if (!text.regionMatches(pos, word, 0, word.length, ignoreCase = true)) return false
            val after = text.getOrNull(pos + word.length)
            if (after != null && after.isLetterOrDigit()) return false
            pos += word.length
            return true
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun skipSpace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
