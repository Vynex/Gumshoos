package com.vynex.gumshoos

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.core.net.toUri

/**
 * A destination the current query can be handed off to when no installed app matches it.
 *
 * [id] is the persistence key: settings will eventually store which providers are enabled and in
 * what order, so it must stay stable even if the label or icon changes.
 */
data class SearchProvider(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
    /** Preferred deep link — usually opens the app directly. */
    val appUri: (String) -> String,
    /** Used when the app isn't installed or can't handle the link. */
    val webUri: (String) -> String,
)

object SearchProviders {

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
        icon = R.drawable.ic_play_store,
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
    )

    val all = listOf(google, playStore, maps, youtube, spotify)

    /**
     * Which providers appear, in order. A plain val for now; settings will replace this with a
     * persisted, reorderable list — hence [SearchProvider.id] being a stable key.
     */
    val enabled: List<SearchProvider> get() = all

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
