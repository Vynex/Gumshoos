package com.vynex.gumshoos

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A destination the current query can be handed off to when no installed app matches it.
 *
 * [id] is the persistence key for the enabled set and the order, so it must stay stable even if the
 * label or icon changes.
 */
data class SearchProvider(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
    /** Preferred deep link — usually opens the app directly. */
    val appUri: (String) -> String,
    /** Used when the app isn't installed or can't handle the link. */
    val webUri: (String) -> String,
    /**
     * Set when the provider is pointless without a particular app. Those are hidden entirely when
     * it isn't installed, rather than falling back to a web page nobody asked for — and the package
     * is forced on the intent so a claimed https link can't be swallowed by the browser.
     */
    val requiresPackage: String? = null,
)

object SearchProviders {

    private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    val google = SearchProvider(
        id = "google",
        label = "Google",
        icon = R.drawable.ic_provider_google,
        appUri = { "" }, // handled specially below via ACTION_WEB_SEARCH
        webUri = { "https://www.google.com/search?q=$it" },
    )

    val playStore = SearchProvider(
        id = "play_store",
        label = "Play Store",
        icon = R.drawable.ic_provider_play_store,
        appUri = { "market://search?q=$it&c=apps" },
        webUri = { "https://play.google.com/store/search?q=$it&c=apps" },
    )

    val maps = SearchProvider(
        id = "maps",
        label = "Maps",
        icon = R.drawable.ic_provider_maps,
        appUri = { "geo:0,0?q=$it" },
        webUri = { "https://www.google.com/maps/search/$it" },
    )

    val youtube = SearchProvider(
        id = "youtube",
        label = "YouTube",
        icon = R.drawable.ic_provider_youtube,
        appUri = { "https://www.youtube.com/results?search_query=$it" },
        webUri = { "https://www.youtube.com/results?search_query=$it" },
    )

    val spotify = SearchProvider(
        id = "spotify",
        label = "Spotify",
        icon = R.drawable.ic_provider_spotify,
        appUri = { "spotify:search:$it" },
        webUri = { "https://open.spotify.com/search/$it" },
        // Like ChatGPT: searching Spotify without Spotify lands on a web player behind a login
        // wall, which is not what tapping the mark was asking for.
        requiresPackage = SPOTIFY_PACKAGE,
    )

    val chatGpt = SearchProvider(
        id = "chatgpt",
        label = "ChatGPT",
        icon = R.drawable.ic_provider_chatgpt,
        // ?q= opens a new chat prefilled with the query rather than resuming the last one.
        appUri = { "https://chatgpt.com/?q=$it" },
        webUri = { "https://chatgpt.com/?q=$it" },
        requiresPackage = CHATGPT_PACKAGE,
    )

    val all = listOf(google, playStore, maps, youtube, spotify, chatGpt)

    /**
     * Providers whose required app isn't installed.
     *
     * Starts out assuming every one of them is missing, so a provider is shown only once its app
     * has actually been found. The other way round flashes a mark that then disappears.
     *
     * Resolved at warm time rather than per keystroke, since checking package installation is a
     * binder call.
     */
    var missing by mutableStateOf(
        all.filter { it.requiresPackage != null }.map { it.id }.toSet()
    )
        private set

    suspend fun refreshAvailability(context: Context) = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val absent = all.filter { provider ->
            val required = provider.requiresPackage ?: return@filter false
            runCatching { pm.getPackageInfo(required, 0) }.isFailure
        }.map { it.id }.toSet()
        withContext(Dispatchers.Main) { missing = absent }
    }

    /**
     * The configured order, applied to whichever providers are passed in.
     *
     * Ids the order doesn't mention sort to the end in their declaration order, which is what makes
     * a provider added in a later version appear at all: it lands at the end of an order that was
     * saved before it existed, rather than being dropped for not being listed.
     */
    fun inConfiguredOrder(
        providers: List<SearchProvider>,
        order: List<String>,
    ): List<SearchProvider> = inConfiguredOrder(providers, order, SearchProvider::id)

    /**
     * The same rule over anything with a stable id — the widget's action icons reuse it.
     *
     * `sortedBy` is stable, which is what makes "unlisted ids keep their declaration order" hold
     * rather than being arbitrary.
     */
    fun <T> inConfiguredOrder(
        items: List<T>,
        order: List<String>,
        id: (T) -> String,
    ): List<T> = items.sortedBy { item ->
        order.indexOf(id(item)).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }

    /**
     * Moves one id within a list, for the reorder buttons. Out-of-range moves are no-ops, so the
     * top item's "up" needs no special case at the call site.
     */
    fun reorder(ids: List<String>, id: String, delta: Int): List<String> {
        val from = ids.indexOf(id)
        if (from < 0) return ids
        val to = from + delta
        if (to !in ids.indices) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * Which providers appear, in order: installed, switched on, and arranged as configured.
     *
     * Disabling is stored as the *off* set rather than the on set, so a provider added later is on
     * by default instead of silently absent for everyone who already has settings saved.
     */
    val enabled: List<SearchProvider>
        get() = inConfiguredOrder(
            all.filterNot { it.id in missing || it.id in GumshoosConfig.disabledProviders },
            GumshoosConfig.providerOrder,
        )

    fun search(context: Context, provider: SearchProvider, query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val encoded = Uri.encode(trimmed)

        // Google gets the system's own web-search intent first, so it honours whichever search app
        // the user has actually chosen rather than forcing google.com.
        if (provider.id == google.id) {
            val webSearch = Intent(Intent.ACTION_WEB_SEARCH)
                .putExtra(SearchManager.QUERY, trimmed)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(webSearch) }.isSuccess) return
        }

        val app = viewIntent(provider.appUri(encoded))
            .apply { provider.requiresPackage?.let(::setPackage) }
        val web = viewIntent(provider.webUri(encoded))
        runCatching { context.startActivity(app) }
            .recoverCatching { context.startActivity(web) }
    }

    /** Opens the store itself rather than a search, for the empty-query case. */
    fun openPlayStore(context: Context) {
        val app = viewIntent("market://search?q=&c=apps")
        val web = viewIntent("https://play.google.com/store/apps")
        runCatching { context.startActivity(app) }
            .recoverCatching { context.startActivity(web) }
    }

    private fun viewIntent(uri: String) =
        Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
