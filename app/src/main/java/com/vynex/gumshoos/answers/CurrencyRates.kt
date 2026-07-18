package com.vynex.gumshoos.answers

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
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
object CurrencyRates {

    private const val TAG = "GumshoosLatency"
    private const val FILE_NAME = "rates.json"
    private val MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(20)

    var table by mutableStateOf<RateTable?>(null)
        private set

    /** So the settings screen can show progress rather than looking inert while a fetch runs. */
    var refreshing by mutableStateOf(false)
        private set

    /**
     * Loads the cache, then refreshes over the network only if it is stale.
     *
     * A failed fetch leaves the cached table in place: stale rates whose age is shown beat no
     * answer at all.
     *
     * @param force skips the freshness check, for a refresh the user asked for explicitly. The
     *   automatic path stays rate-limited so every warm-up doesn't hit the network.
     * @return whether usable rates are now loaded.
     */
    suspend fun refresh(context: Context, base: String, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val cached = table ?: readCache(context)
            if (cached != null && table == null) {
                withContext(Dispatchers.Main) { table = cached }
            }

            val fresh = cached != null &&
                cached.base == base &&
                System.currentTimeMillis() - cached.fetchedAtMillis < MAX_AGE_MILLIS
            if (fresh && !force) return@withContext true

            withContext(Dispatchers.Main) { refreshing = true }
            try {
                val fetched = runCatching { fetch(base) }
                    .onFailure { Log.w(TAG, "rate fetch failed; keeping cached rates", it) }
                    .getOrNull() ?: return@withContext false

                writeCache(context, fetched)
                withContext(Dispatchers.Main) { table = fetched }
                return@withContext true
            } finally {
                withContext(Dispatchers.Main) { refreshing = false }
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
