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
    /**
     * Set when the provider is pointless without a particular app. Those are hidden entirely when
     * it isn't installed, rather than falling back to a web page nobody asked for — and the package
     * is forced on the intent so a claimed https link can't be swallowed by the browser.
     */
    val requiresPackage: String? = null,
)

object SearchProviders {

    private const val CHATGPT_PACKAGE = "com.openai.chatgpt"

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
     * Providers whose required app is present. Resolved at warm time rather than per keystroke,
     * since checking package installation is a binder call.
     */
    private var available by mutableStateOf(all.filter { it.requiresPackage == null })

    suspend fun refreshAvailability(context: Context) = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val present = all.filter { provider ->
            val required = provider.requiresPackage ?: return@filter true
            runCatching { pm.getPackageInfo(required, 0) }.isSuccess
        }
        withContext(Dispatchers.Main) { available = present }
    }

    /**
     * Which providers appear, in order. Filtered only by availability for now; settings will layer
     * a persisted, reorderable selection on top — hence [SearchProvider.id] being a stable key.
     */
    val enabled: List<SearchProvider> get() = available

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
