package com.vynex.gumshoos

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.MainThread
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
    /**
     * Whether "open this in a browser instead" is a choice rather than a fallback.
     *
     * True for the two general search engines and nothing else: their [webUri] runs the *same*
     * search as their app, so which one gets it is a preference. Maps, YouTube, Spotify and ChatGPT
     * have a web page too, but it is what you get when the app isn't there — nobody picks it — so
     * offering the switch would be inventing a decision.
     *
     * A provider with this set is never hidden for a missing app while it is in browser mode:
     * [requiresPackage] gates the app route, and the browser route doesn't use it.
     */
    val canUseBrowser: Boolean = false,
)

object SearchProviders {

    private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    private const val SPOTIFY_PACKAGE = "com.spotify.music"
    private const val DUCKDUCKGO_PACKAGE = "com.duckduckgo.mobile.android"

    val google = SearchProvider(
        id = "google",
        label = "Google",
        icon = R.drawable.ic_provider_google,
        appUri = { "" }, // handled specially below via ACTION_WEB_SEARCH
        webUri = { "https://www.google.com/search?q=$it" },
        canUseBrowser = true,
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

    val duckDuckGo = SearchProvider(
        id = "duckduckgo",
        label = "DuckDuckGo",
        icon = R.drawable.ic_provider_duckduckgo,
        // The app is a browser, so it takes any https link and the forced package is what keeps this
        // out of the default one. Both forms are the same URL: there is no scheme of DuckDuckGo's own
        // that reaches the app, and inventing one would be a rung that starts and draws nothing.
        appUri = { "https://duckduckgo.com/?q=$it" },
        webUri = { "https://duckduckgo.com/?q=$it" },
        // Gated, though duckduckgo.com works perfectly well in any browser — the point of choosing it
        // over Google here is the app, and a row that quietly opened Chrome instead would be
        // answering a different question. The browser is reachable, but only by asking for it: see
        // canUseBrowser, which is what turns the gate off.
        requiresPackage = DUCKDUCKGO_PACKAGE,
        canUseBrowser = true,
    )

    /**
     * Declaration order, which is also the default order — [GumshoosConfig.providerOrder] is empty
     * until something is dragged.
     *
     * A provider added here goes at the **end**, and not because the end is where new things go: an
     * id an existing saved order has never heard of sorts last anyway, so any other position would
     * make a fresh install disagree with an upgraded one about a list neither user has touched.
     */
    val all = listOf(google, playStore, maps, youtube, spotify, chatGpt, duckDuckGo)

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
        val absent = resolveMissing(context)
        withContext(Dispatchers.Main) { missing = absent }
    }

    /**
     * The same answer, resolved on the calling thread.
     *
     * For the settings screen, which needs it in the composition that draws the list rather than a
     * frame later: [missing] starts out pessimistic, so a suspending refresh would put every gated
     * provider under "Uninstalled" and then move it up as the answer arrived. Two `getPackageInfo`
     * calls is a cost a settings screen can afford, and the widget bar's Actions list already pays
     * it for the same reason.
     */
    @MainThread
    fun refreshAvailabilityNow(context: Context): Set<String> =
        resolveMissing(context).also { missing = it }

    private fun resolveMissing(context: Context): Set<String> {
        val pm = context.packageManager
        return all.filter { provider ->
            val required = provider.requiresPackage ?: return@filter false
            runCatching { pm.getPackageInfo(required, 0) }.isFailure
        }.map { it.id }.toSet()
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
     * Applies a move made among [visible] rows to the full saved order.
     *
     * A drag reports positions within the rows on screen, but the widget bar saves an order over
     * *every* action — including the ones whose app isn't installed, which are drawn in a separate
     * group and cannot be dragged. Those have to keep their place, so that installing the app
     * returns the action to where it sat rather than to the end of the list.
     *
     * So the visible ids are reordered among themselves and then poured back into the slots the
     * visible ids already occupied; anything not on screen stays at exactly its own index. Where
     * every id is visible — the Providers screen — that degrades to a plain move.
     *
     * A move off either end is a no-op, which is what lets the "move up" accessibility action on
     * the top row be wired without a special case at the call site.
     */
    fun reorderVisible(
        ids: List<String>,
        visible: List<String>,
        from: Int,
        to: Int,
    ): List<String> {
        if (from == to || from !in visible.indices || to !in visible.indices) return ids
        val onScreen = visible.toSet()
        // A visible id the saved order has never heard of would leave the pour short and throw.
        // It can't happen from the screens, but it costs one count to not depend on that.
        if (ids.count { it in onScreen } != visible.size) return ids
        val moved = visible.toMutableList().apply { add(to, removeAt(from)) }.iterator()
        return ids.map { if (it in onScreen) moved.next() else it }
    }

    /**
     * Whether [provider] can't run because the app it needs isn't installed.
     *
     * A provider in browser mode is never unavailable: the gate exists to stop a row promising an
     * app that isn't there, and the browser route never touches that app. This is what lets
     * DuckDuckGo be app-gated *and* still reachable — the switch is the way past the gate.
     *
     * Both sets are parameters with defaults rather than reads taken inside. [absent] is so the
     * settings screen can pass the availability it just resolved for itself instead of the
     * warm-time [missing]; [browserMode] is so this rule can be tested at all, since the config it
     * would otherwise read can only be written through a Context.
     */
    fun needsMissingApp(
        provider: SearchProvider,
        absent: Set<String> = missing,
        browserMode: Set<String> = GumshoosConfig.browserProviders,
    ): Boolean = provider.id in absent && provider.id !in browserMode

    /**
     * Which providers appear, in order: runnable, switched on, and arranged as configured.
     *
     * Disabling is stored as the *off* set rather than the on set, so a provider added later is on
     * by default instead of silently absent for everyone who already has settings saved.
     */
    val enabled: List<SearchProvider>
        get() = inConfiguredOrder(
            all.filterNot { needsMissingApp(it) || it.id in GumshoosConfig.disabledProviders },
            GumshoosConfig.providerOrder,
        )

    fun search(context: Context, provider: SearchProvider, query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val encoded = Uri.encode(trimmed)

        // Browser mode goes straight to the web URI, skipping both the app link and — for Google —
        // ACTION_WEB_SEARCH, which resolves to whichever *search app* the phone prefers and is
        // therefore the opposite of what was asked for.
        if (provider.canUseBrowser && GumshoosConfig.opensInBrowser(provider.id)) {
            openInBrowser(context, provider, encoded)
            return
        }

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

    /**
     * The web URI, in the chosen browser where there is one.
     *
     * The forced attempt is tried first and the unforced one catches it, because a browser can be
     * uninstalled long after it was picked and `setPackage` then makes the intent resolve to
     * nothing. Recorded rather than swallowed: falling back lands somewhere plausible, so the only
     * sign that the setting has quietly stopped applying is this line.
     */
    private fun openInBrowser(context: Context, provider: SearchProvider, encoded: String) {
        val uri = provider.webUri(encoded)
        val chosen = GumshoosConfig.preferredBrowser
        if (chosen != null) {
            val forced = viewIntent(uri).setPackage(chosen)
            if (runCatching { context.startActivity(forced) }.isSuccess) return
            EventLog.record("browser $chosen didn't take ${provider.id}; using the default")
        }
        runCatching { context.startActivity(viewIntent(uri)) }
            .onFailure { EventLog.record("no browser handled ${provider.id}") }
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
