package com.vynex.gumshoos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Where live query completions come from.
 *
 * Both endpoints are the ones the browsers' own address bars use. Neither is a documented public
 * API, which is the reason [WebSuggestions.parse] treats every part of the response as optional
 * rather than trusting a shape: the failure to design for is a format that changes without notice,
 * and the right response to it is no suggestions rather than a crash on the typing path.
 */
enum class SuggestionSource(
    val id: String,
    val label: String,
    /** Query is appended already encoded. */
    private val urlPrefix: String,
) {
    GOOGLE(
        id = "google",
        label = "Google",
        // client=firefox is what selects the plain two-element array. The default client returns
        // XML, and the Chrome clients add fields we would only throw away.
        urlPrefix = "https://suggestqueries.google.com/complete/search?client=firefox&q=",
    ),
    DUCKDUCKGO(
        id = "duckduckgo",
        label = "DuckDuckGo",
        // type=list is what matters: without it the reply is [{"phrase":"…"}, …], a second shape to
        // parse for no gain. With it, DuckDuckGo answers in exactly Google's format.
        urlPrefix = "https://duckduckgo.com/ac/?type=list&q=",
    ),
    ;

    fun urlFor(query: String): String = urlPrefix + URLEncoder.encode(query, "UTF-8")

    companion object {
        val DEFAULT = GOOGLE

        /** Unknown ids fall back rather than failing, as everywhere else config is loaded. */
        fun fromId(id: String?): SuggestionSource =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Live completions for the current query, from Google or DuckDuckGo.
 *
 * **Off by default, and it has to be**: every query typed here is sent to a search engine, which is
 * not something to opt somebody into. The settings screen says so in as many words.
 *
 * This is the only network call on an interactive path in the app — the exchange rates fetch at warm
 * time is the other one, and it is deliberately nowhere near a keystroke. What keeps it off the show
 * path: nothing runs until the window is already open and five characters are typed, the debounce
 * outlasts ordinary typing, and the results feed a section that is absent until they arrive.
 */
object WebSuggestions {

    /**
     * Below this, nothing is sent.
     *
     * Short queries are both the least useful to complete and the most revealing per character, and
     * the app grid has almost certainly already answered a three-letter query anyway.
     */
    const val MIN_QUERY_LENGTH = 5

    /**
     * How long typing must stop before anything is sent.
     *
     * Deliberately longer than a gap between keystrokes — at a typical 200-300ms per character this
     * fires once, after a pause, rather than once per letter. That is the whole cost control: the
     * endpoint is free and fast, but a request per keystroke would put a dozen queries on the wire
     * for one search and give the section a chance to resize under a moving finger.
     */
    const val DEBOUNCE_MILLIS = 400L

    /**
     * Rows shown, matching the contacts section for the same reason: the card's height is derived,
     * so an unbounded list would make it unbounded too.
     */
    const val MAX_RESULTS = 3

    /** Short — this is interactive, and a slow answer is worth less than a fast absence. */
    private const val TIMEOUT_MILLIS = 3_000

    /**
     * What is on screen, until something replaces it.
     *
     * Rows are **not** dropped when the query changes. Doing that was the first implementation and
     * it read badly: typing on past a first set of suggestions collapsed the section and re-expanded
     * it a moment later, so every pause in typing cost a fold and an unfold. Holding the previous
     * rows until an answer replaces them makes the common case — three rows becoming three other
     * rows — a text swap at a constant height, with no layout animation at all.
     *
     * The rows can therefore be one query behind for the length of a debounce plus a round trip.
     * That is the trade, and it is the right way round: a completion for a prefix of what is typed
     * is still a completion, and tapping it searches for the text on the row rather than for the
     * query, so nothing it offers is untrue.
     *
     * A reply for a query already abandoned cannot overwrite a newer one — the effect driving this
     * is cancelled when the query changes, and cancellation is checked on the way back to the main
     * thread, so a slow request that has already left simply never publishes.
     */
    var results by mutableStateOf<List<String>>(emptyList())
        private set

    /** Whether [query] is one this is willing to send. Pure, so the policy is testable. */
    fun shouldFetch(query: String, enabled: Boolean): Boolean =
        enabled && query.trim().length >= MIN_QUERY_LENGTH

    /**
     * Where a tapped suggestion is searched: the provider matching the engine that suggested it.
     *
     * Falls back to the first enabled provider, and then to nothing. The engine's own provider can
     * be switched off or — DuckDuckGo — hidden for a missing app, and completing a query with one
     * engine only to search with another is a worse answer than the fallback, but still a better
     * one than a row that does nothing when tapped.
     */
    fun searchProviderFor(source: SuggestionSource): SearchProvider? {
        val preferred = when (source) {
            SuggestionSource.GOOGLE -> SearchProviders.google
            SuggestionSource.DUCKDUCKGO -> SearchProviders.duckDuckGo
        }
        val enabled = SearchProviders.enabled
        return preferred.takeIf { it in enabled } ?: enabled.firstOrNull()
    }

    /**
     * Drops whatever is showing.
     *
     * Only for the cases where there is no longer a query these rows could belong to at all — the
     * window closed, the feature was switched off, the engine changed, or the query fell back below
     * [MIN_QUERY_LENGTH]. Not called merely because the query changed; see [results].
     */
    fun clear() {
        results = emptyList()
    }

    /**
     * Fetches completions for [query] and publishes them.
     *
     * Failure is silent to the user and recorded for us: no network, a changed format and a blocked
     * endpoint are indistinguishable from "nothing to suggest" on screen, which is the correct
     * appearance and a useless diagnosis.
     */
    suspend fun refresh(source: SuggestionSource, query: String) {
        val trimmed = query.trim()
        val fetched = withContext(Dispatchers.IO) {
            runCatching { fetch(source, trimmed) }
                .onFailure { EventLog.record("suggest ${source.id} FAILED ${it.javaClass.simpleName}") }
                .getOrNull()
        } ?: return

        // A failed fetch returned above rather than landing here, so a dropped connection leaves the
        // previous rows alone instead of blanking the section. An empty *successful* reply does
        // replace them: the engine has said there is nothing for this query, which is an answer.
        withContext(Dispatchers.Main) { results = fetched }
    }

    private fun fetch(source: SuggestionSource, query: String): List<String> {
        val connection = URL(source.urlFor(query)).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) emptyList()
            else parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The suggestion strings out of a completion response.
     *
     * Both sources answer `["query", ["first", "second", …], …]` — Google because `client=firefox`
     * asks for that shape, DuckDuckGo because `type=list` does. Google appends two more elements
     * that are ignored, which is why this reads element 1 rather than expecting a length.
     *
     * Anything unexpected is an empty list, never an exception: this runs while somebody is typing,
     * and neither endpoint promises us a format.
     */
    fun parse(body: String): List<String> = runCatching {
        val suggestions = JSONArray(body).optJSONArray(1) ?: return emptyList()
        (0 until suggestions.length())
            .mapNotNull { suggestions.optString(it).takeIf(String::isNotBlank) }
            .distinct()
            .take(MAX_RESULTS)
    }.getOrDefault(emptyList())
}
