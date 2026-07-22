package com.vynex.gumshoos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The parts of web suggestions that are pure: what comes out of a completion response, and whether
 * a query is one we're willing to send at all.
 *
 * The bodies below are **real replies**, captured from both endpoints rather than written by hand —
 * the whole reason one parser can serve both is that `client=firefox` and `type=list` produce the
 * same shape, and a hand-written fixture would agree with that belief instead of testing it.
 *
 * Neither endpoint is documented, so the negative cases matter more than usual: this code runs while
 * somebody is typing, and a format change has to come out as no suggestions rather than a crash.
 */
class WebSuggestionsTest {

    private val googleBody = """
        ["whats",["whatsapp","whatsapp web","whatsapp download","whatsapp app","whatsapp login"],
        [],{"google:suggestsubtypes":[[512,433,131],[512,433,131],[512,433,131],[512,433],[512]]}]
    """.trimIndent()

    private val duckDuckGoBody =
        """["whats",["whatsapp web","whatsapp","whatsapp download","whatsapp login"]]"""

    // --- parsing ---------------------------------------------------------------------------------

    @Test
    fun `reads google's suggestions`() {
        assertEquals(
            listOf("whatsapp", "whatsapp web", "whatsapp download"),
            WebSuggestions.parse(googleBody),
        )
    }

    /** The point of type=list: the same element 1, so nothing here branches on the source. */
    @Test
    fun `reads duckduckgo's suggestions`() {
        assertEquals(
            listOf("whatsapp web", "whatsapp", "whatsapp download"),
            WebSuggestions.parse(duckDuckGoBody),
        )
    }

    /** Google appends two elements DuckDuckGo doesn't; reading by index rather than length is why. */
    @Test
    fun `trailing elements are ignored`() {
        assertEquals(WebSuggestions.parse(duckDuckGoBody).size, WebSuggestions.parse(googleBody).size)
    }

    @Test
    fun `caps at three`() {
        val body = """["q",["one","two","three","four","five","six"]]"""
        assertEquals(listOf("one", "two", "three"), WebSuggestions.parse(body))
    }

    /** Google repeats a completion often enough to matter; three rows can't afford a duplicate. */
    @Test
    fun `drops duplicates before capping`() {
        val body = """["q",["one","one","two","three"]]"""
        assertEquals(listOf("one", "two", "three"), WebSuggestions.parse(body))
    }

    @Test
    fun `drops blanks`() {
        assertEquals(listOf("one"), WebSuggestions.parse("""["q",["one","","   "]]"""))
    }

    // --- the negatives, which are the ones that keep a keystroke from crashing --------------------

    @Test
    fun `no suggestions is empty, not an error`() {
        assertEquals(emptyList<String>(), WebSuggestions.parse("""["zxqwv",[]]"""))
    }

    @Test
    fun `malformed json is empty`() {
        listOf("", "not json", "{", "<html>rate limited</html>").forEach { body ->
            assertEquals("body: $body", emptyList<String>(), WebSuggestions.parse(body))
        }
    }

    /** A changed shape — an object where the array was, or the array missing entirely. */
    @Test
    fun `an unexpected shape is empty`() {
        listOf(
            """{"suggestions":["one"]}""",
            """["q"]""",
            """["q",{"phrase":"one"}]""",
            """[]""",
        ).forEach { body ->
            assertEquals("body: $body", emptyList<String>(), WebSuggestions.parse(body))
        }
    }

    // --- when anything is sent at all -------------------------------------------------------------

    @Test
    fun `nothing is sent while the feature is off`() {
        assertEquals(false, WebSuggestions.shouldFetch("whatsapp", enabled = false))
    }

    @Test
    fun `nothing is sent below the character floor`() {
        val short = "a".repeat(WebSuggestions.MIN_QUERY_LENGTH - 1)
        assertEquals(false, WebSuggestions.shouldFetch(short, enabled = true))
    }

    @Test
    fun `the floor counts trimmed characters`() {
        // Four letters and a space is four letters. Otherwise trailing whitespace, which the field
        // produces constantly, would tip a query over the floor a keystroke early.
        val padded = "a".repeat(WebSuggestions.MIN_QUERY_LENGTH - 1) + " "
        assertEquals(false, WebSuggestions.shouldFetch(padded, enabled = true))
    }

    @Test
    fun `the floor itself is enough`() {
        val exact = "a".repeat(WebSuggestions.MIN_QUERY_LENGTH)
        assertEquals(true, WebSuggestions.shouldFetch(exact, enabled = true))
    }

    // --- the URLs, since a wrong parameter silently changes the response's shape --------------------

    @Test
    fun `google is asked for the firefox client`() {
        val url = SuggestionSource.GOOGLE.urlFor("whats up")
        assertEquals(true, url.contains("client=firefox"))
        assertEquals(true, url.endsWith("q=whats+up"))
    }

    /** Without type=list DuckDuckGo answers [{"phrase":…}], which element 1 would never find. */
    @Test
    fun `duckduckgo is asked for the list form`() {
        val url = SuggestionSource.DUCKDUCKGO.urlFor("whats up")
        assertEquals(true, url.contains("type=list"))
        assertEquals(true, url.endsWith("q=whats+up"))
    }

    @Test
    fun `an unknown saved source falls back rather than failing`() {
        assertEquals(SuggestionSource.DEFAULT, SuggestionSource.fromId("bing"))
        assertEquals(SuggestionSource.DEFAULT, SuggestionSource.fromId(null))
    }

    @Test
    fun `source ids are stable keys`() {
        SuggestionSource.entries.forEach { source ->
            assertEquals(source, SuggestionSource.fromId(source.id))
        }
    }
}
