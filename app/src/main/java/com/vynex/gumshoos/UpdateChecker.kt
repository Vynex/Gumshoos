package com.vynex.gumshoos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Whether a newer release exists on GitHub, for the About card at the foot of settings.
 *
 * GitHub releases are the source of truth; `releases/latest` already excludes drafts and
 * prereleases, so the tag it returns is a real, published version and the comparison against what
 * is installed is a plain [Semver] one.
 *
 * This is not on any interactive path — it runs when the settings root is composed, never near the
 * search window — so it fetches on demand rather than at warm time. A slow or absent network shows
 * no badge and no error: an update that cannot be confirmed is indistinguishable, and should look
 * identical, to being up to date.
 */
object UpdateChecker {

    private const val OWNER = "vynex"
    private const val REPO = "gumshoos"

    /** Who the card credits, kept here so the string lives beside the repo it points at. */
    const val AUTHOR = "Kushagra Shukla"

    const val REPO_URL = "https://github.com/$OWNER/$REPO"
    const val RELEASES_URL = "$REPO_URL/releases"

    /**
     * The newest release's own page. GitHub's `/releases/latest` permanently redirects to whichever
     * release is current, so this needs no tag threaded through it — used only when an update is
     * live, where "latest" is by definition the one worth landing on.
     */
    const val LATEST_RELEASE_URL = "$REPO_URL/releases/latest"

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** Short — this runs off a screen the user is looking at, and a stale badge beats a hung one. */
    private const val TIMEOUT_MILLIS = 5_000

    /**
     * Floor between two network checks, across the whole process.
     *
     * The check fires from a `LaunchedEffect` on the settings root, so every return to Home would
     * otherwise hit GitHub again; unauthenticated the rate limit is 60/hour and there is nothing to
     * learn from asking twice in a session anyway. Held in memory rather than persisted — a process
     * restart is a fine reason to check again.
     */
    private val MIN_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(6)

    /** The newest published version, once known — normalised, so `v1.2.0` reads `1.2.0`. */
    var latestVersion by mutableStateOf<String?>(null)
        private set

    /** True only when a strictly newer release than the installed build has been confirmed. */
    var updateAvailable by mutableStateOf(false)
        private set

    private var lastCheckAtMillis = 0L
    private var inFlight = false

    /**
     * Compares the latest release against [installedVersionName], updating the observable state.
     *
     * The installed name is passed in rather than read from `BuildConfig` here, so the comparison
     * stays testable and the object stays free of the build config. A name that is not a version at
     * all returns early — there is nothing to compare against.
     *
     * @param force skips the interval throttle, for a check the user asked for explicitly.
     */
    suspend fun check(installedVersionName: String, force: Boolean = false) {
        val installed = Semver.parse(installedVersionName) ?: return

        val now = System.currentTimeMillis()
        if (!force && lastCheckAtMillis != 0L && now - lastCheckAtMillis < MIN_INTERVAL_MILLIS) return
        if (inFlight) return
        inFlight = true
        lastCheckAtMillis = now

        try {
            val tag = withContext(Dispatchers.IO) {
                runCatching { fetchLatestTag() }
                    .onFailure { EventLog.record("update check FAILED ${it.javaClass.simpleName}") }
                    .getOrNull()
            } ?: return

            val latest = Semver.parse(tag) ?: return
            latestVersion = latest.toString()
            updateAvailable = latest > installed
            EventLog.record(
                "update check latest=$latest installed=$installed available=$updateAvailable",
            )
        } finally {
            inFlight = false
        }
    }

    /** The `tag_name` of the latest release, or null on any non-OK reply or missing field. */
    private fun fetchLatestTag(): String? {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects a request with no User-Agent outright, so this is not optional.
            setRequestProperty("User-Agent", "Gumshoos")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .optString("tag_name")
                    .takeIf { it.isNotBlank() }
            }
        } finally {
            connection.disconnect()
        }
    }
}
