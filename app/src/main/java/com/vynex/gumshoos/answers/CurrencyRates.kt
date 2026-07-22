package com.vynex.gumshoos.answers

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Exchange rates from open.er-api.com — no API key, 160+ currencies, updated daily.
 *
 * HttpURLConnection and org.json, both in the platform: one daily GET returning a flat map of
 * numbers does not justify adding OkHttp and a serialization library to a project that has needed
 * neither so far.
 *
 * Rates are cached to disk and survive being offline. What is never done is fetching on the show
 * path — the same rule as the app index and usage stats.
 */
/** What a [CurrencyRates.refresh] call actually did, so a caller can tell a refusal from a failure. */
enum class RateRefreshOutcome {
    /** New rates were downloaded. */
    UPDATED,

    /** No fetch was needed — the rates already loaded are recent enough. */
    ALREADY_FRESH,

    /** Declined: the last attempt was too recent. Whatever was already loaded is untouched. */
    THROTTLED,

    /** The service couldn't be reached, or answered with something unusable. */
    FAILED,
}

object CurrencyRates {

    private const val TAG = "GumshoosLatency"
    private const val FILE_NAME = "rates.json"
    private val MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(20)

    /**
     * Floor on the gap between two network attempts, whichever caller asks.
     *
     * The rates publish once a day, so nothing is lost by refusing; what this stops is the settings
     * button being held down. `refreshing` alone never did — it is only true for the length of the
     * request, so a fast reply re-enabled the row within a few hundred milliseconds. A minute is
     * short enough that a real retry — the first attempt failed, the network is back — still gets
     * through quickly.
     */
    private val MIN_FETCH_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(60)

    /**
     * Serialises the whole load-check-fetch-write sequence.
     *
     * Two taps landing in the same frame both saw `refreshing == false` and both fetched, racing on
     * the cache file. Now the second waits, then reads the cooldown the first one set.
     */
    private val lock = Mutex()

    var table by mutableStateOf<RateTable?>(null)
        private set

    /** So the settings screen can show progress rather than looking inert while a fetch runs. */
    var refreshing by mutableStateOf(false)
        private set

    /**
     * When the network was last reached for, successfully or not. Zero until the first attempt.
     *
     * Compose state so a screen gating on the cooldown recomposes when an attempt starts, with no
     * observer of its own. It is deliberately *not* [RateTable.fetchedAtMillis], which is when the
     * service published the rates rather than when we asked it for them.
     */
    var lastAttemptAtMillis by mutableStateOf(0L)
        private set

    /** How much longer [refresh] will refuse to hit the network for. Zero if it wouldn't. */
    fun cooldownRemainingMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        if (lastAttemptAtMillis == 0L) return 0L
        // A clock moved backwards would otherwise strand the cooldown for as long as the shift.
        val elapsed = nowMillis - lastAttemptAtMillis
        if (elapsed < 0) return 0L
        return (MIN_FETCH_INTERVAL_MILLIS - elapsed).coerceAtLeast(0L)
    }

    /**
     * Loads the cache, then refreshes over the network only if it is stale.
     *
     * A failed fetch leaves the cached table in place: stale rates whose age is shown beat no
     * answer at all.
     *
     * @param force skips the freshness check, for a refresh the user asked for explicitly. It does
     *   not skip the cooldown — that one is the ceiling on how often we may ask at all, and no
     *   caller is allowed past it.
     */
    suspend fun refresh(
        context: Context,
        base: String,
        force: Boolean = false,
    ): RateRefreshOutcome = withContext(Dispatchers.IO) {
        lock.withLock {
            val cached = table ?: readCache(context)
            if (cached != null && table == null) {
                withContext(Dispatchers.Main) { table = cached }
            }

            val fresh = cached != null &&
                cached.base == base &&
                System.currentTimeMillis() - cached.fetchedAtMillis < MAX_AGE_MILLIS
            if (fresh && !force) return@withLock RateRefreshOutcome.ALREADY_FRESH
            if (cooldownRemainingMillis() > 0) return@withLock RateRefreshOutcome.THROTTLED

            withContext(Dispatchers.Main) {
                refreshing = true
                // Stamped before the request, not after it, so a slow reply doesn't extend the wait.
                lastAttemptAtMillis = System.currentTimeMillis()
            }
            try {
                val fetched = runCatching { fetch(base) }
                    .onFailure { Log.w(TAG, "rate fetch failed; keeping cached rates", it) }
                    .getOrNull() ?: return@withLock RateRefreshOutcome.FAILED

                writeCache(context, fetched)
                withContext(Dispatchers.Main) { table = fetched }
                return@withLock RateRefreshOutcome.UPDATED
            } finally {
                withContext(Dispatchers.Main) { refreshing = false }
            }
        }
    }

    private fun fetch(base: String): RateTable? {
        val connection = URL("https://open.er-api.com/v6/latest/$base")
            .openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            // Short: this runs at warm time and must not hold the coroutine open if the network is
            // unreachable. Failing fast and keeping the cache is the better outcome.
            connectTimeout = 5_000
            readTimeout = 5_000
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body, base)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String, base: String): RateTable? {
        val json = JSONObject(body)
        if (json.optString("result") != "success") return null
        val rates = json.optJSONObject("rates") ?: return null

        val parsed = buildMap {
            rates.keys().forEach { code -> put(code, rates.getDouble(code)) }
        }
        if (parsed.isEmpty()) return null

        return RateTable(
            base = json.optString("base_code", base),
            rates = parsed,
            // The provider dates its answer from this, so it reflects when the rates were published
            // rather than when they happened to be downloaded.
            fetchedAtMillis = json.optLong("time_last_update_unix", 0L)
                .takeIf { it > 0 }?.let { it * 1000 }
                ?: System.currentTimeMillis(),
        )
    }

    private fun readCache(context: Context): RateTable? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val rates = json.getJSONObject("rates")
        RateTable(
            base = json.getString("base"),
            rates = buildMap { rates.keys().forEach { put(it, rates.getDouble(it)) } },
            fetchedAtMillis = json.getLong("fetchedAt"),
        )
    }.getOrNull()

    private fun writeCache(context: Context, table: RateTable) {
        runCatching {
            val json = JSONObject().apply {
                put("base", table.base)
                put("fetchedAt", table.fetchedAtMillis)
                put("rates", JSONObject(table.rates as Map<*, *>))
            }
            File(context.filesDir, FILE_NAME).writeText(json.toString())
        }
    }
}
