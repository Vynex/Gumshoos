package com.vynex.gumshoos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.vynex.gumshoos.widget.GeocodePlace
import com.vynex.gumshoos.widget.WeatherReading
import com.vynex.gumshoos.widget.WidgetAction
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Current conditions for the widget bar, fetched and cached.
 *
 * **Never on the show path.** The overlay never touches this; the only readers are the widget's own
 * update and the settings preview, both of which draw whatever is already cached. A fetch happens on
 * a background thread from a small number of triggers and pushes new RemoteViews when it lands.
 *
 * `HttpURLConnection` and `org.json`, like [WebSuggestions] and the exchange rates — no dependency
 * was added for this and none should be.
 *
 * ## Staleness is the hazard, not failure
 *
 * A failed fetch is obvious to nobody, and a widget keeps whatever it was last handed. So a
 * temperature from this morning would sit on the home screen all day reading like a current one,
 * with nothing on the bar to give it away — the same class of wrong-answer-that-looks-right as a
 * date that never rolls over, but worse, because a date at least looks wrong once you notice it and
 * a plausible temperature never does.
 *
 * Hence [MAX_AGE_MILLIS]: past it the bar draws [STALE_TEXT] rather than the last number. The slot
 * keeps its place instead of vanishing, because a bar that reflows when the network drops is a
 * second mystery on top of the first.
 */
object WeatherRepository {

    /** Past this, the cached reading stops being drawn. Roughly six refresh intervals. */
    private const val MAX_AGE_MILLIS = 3L * 60 * 60 * 1000

    /** Drawn in place of a temperature nobody can vouch for. */
    const val STALE_TEXT = "—"

    private const val WEATHER_URL = "https://api.openweathermap.org/data/2.5/weather"
    private const val GEOCODE_URL = "https://api.openweathermap.org/geo/1.0/direct"

    private const val PREFS = "weather"
    private const val KEY_TEMP = "temp_c"
    private const val KEY_ICON = "icon"
    private const val KEY_PLACE = "place"
    private const val KEY_AT = "read_at"

    @Volatile
    private var cached: WeatherReading? = null

    @Volatile
    private var readAt: Long = 0L

    /** Loaded in [GumshoosApp.onCreate], since the widget provider can be first to run. */
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        readAt = p.getLong(KEY_AT, 0L)
        val temp = p.getFloat(KEY_TEMP, Float.NaN)
        cached = if (temp.isNaN()) {
            null
        } else {
            WeatherReading(
                tempC = temp.toDouble(),
                icon = p.getString(KEY_ICON, "").orEmpty(),
                description = "",
                place = p.getString(KEY_PLACE, "").orEmpty(),
            )
        }
    }

    /**
     * What the bar should draw, or null when the action has nothing to say at all.
     *
     * Null only before the first successful fetch — after that it is a temperature or [STALE_TEXT],
     * never an empty slot, so the bar's layout stops moving once it has been used once.
     */
    fun displayText(now: Long = System.currentTimeMillis()): String? {
        val reading = cached ?: return null
        if (now - readAt > MAX_AGE_MILLIS) return STALE_TEXT
        return reading.format(GumshoosConfig.weatherUnit)
    }

    /** The cached condition code, or null when there is nothing current to draw a mark for. */
    fun conditionCode(now: Long = System.currentTimeMillis()): String? {
        val reading = cached ?: return null
        if (now - readAt > MAX_AGE_MILLIS) return null
        return reading.icon.ifBlank { null }
    }

    fun isConfigured(): Boolean = GumshoosConfig.weatherApiKey.isNotBlank()

    /** Floor on how often the network is touched, however many triggers fire. */
    private const val MIN_INTERVAL_MILLIS = 15L * 60 * 1000

    @Volatile
    private var lastAttempt = 0L

    /**
     * Fetches in the background if it is due, and re-pushes the widgets when the value changed.
     *
     * Safe to call from anywhere and often — several triggers converge here on purpose, since a
     * widget drawing from a device fact needs more than one way to become current: the bar's own
     * `updatePeriodMillis`, settings resuming, and the service warming. [MIN_INTERVAL_MILLIS] is
     * what stops three of them at once becoming three requests.
     *
     * The re-push is conditional on the reading actually changing, because a widget update is not
     * free — the launcher redraws on every one, and the bar's background is re-rasterised per widget.
     *
     * A bare thread rather than a coroutine or WorkManager: this is one blocking call with no
     * cancellation story and no dependency worth adding for it. Nothing awaits the result.
     */
    fun refreshIfDue(context: Context) {
        if (!isConfigured()) return
        if (WidgetAction.WEATHER.id !in GumshoosConfig.widgetBarActions) return
        val now = System.currentTimeMillis()
        if (now - lastAttempt < MIN_INTERVAL_MILLIS) return
        lastAttempt = now

        val app = context.applicationContext
        Thread {
            if (refreshBlocking(app)) refreshAllWidgets(app)
        }.start()
    }

    /**
     * Fetches on the calling thread. Callers must not be on the main one.
     *
     * Returns whether the cache changed, so a caller can skip re-pushing RemoteViews it does not
     * need to — a widget update is not free and the launcher redraws on every one.
     */
    fun refreshBlocking(context: Context): Boolean {
        val key = GumshoosConfig.weatherApiKey
        if (key.isBlank()) return false

        val point = resolveLocation(context, key) ?: run {
            EventLog.record("weather: no location; place='${GumshoosConfig.weatherPlace}'")
            return false
        }

        // units=metric always. The user's C/F choice is applied when drawing, so switching it needs
        // no network at all and cannot leave the cached value in the wrong scale.
        val url = "$WEATHER_URL?lat=${point.first}&lon=${point.second}&units=metric&appid=$key"
        val body = get(url) ?: run {
            EventLog.record("weather: fetch failed")
            return false
        }
        val reading = WeatherReading.parse(body) ?: run {
            // The body is deliberately not logged: it carries the API key back in some error
            // shapes, and the trace is copied out of the app by hand.
            EventLog.record("weather: reply was not a reading (bad key, rate limit or outage)")
            return false
        }

        cached = reading
        readAt = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_TEMP, reading.tempC.toFloat())
            .putString(KEY_ICON, reading.icon)
            .putString(KEY_PLACE, reading.place)
            .putLong(KEY_AT, readAt)
            .apply()
        EventLog.record("weather: ${reading.format(GumshoosConfig.weatherUnit)} at ${reading.place}")
        return true
    }

    /**
     * Where to ask about, as lat to lon.
     *
     * A typed place wins over the device, because someone who typed one meant it — and it is the
     * only route that works with the location permission refused. Falling back the other way would
     * make a refused permission silently change *which city* the bar reports, which is a wrong
     * answer rather than a missing one.
     */
    private fun resolveLocation(context: Context, key: String): Pair<Double, Double>? {
        val lat = GumshoosConfig.weatherLat
        val lon = GumshoosConfig.weatherLon
        if (GumshoosConfig.weatherPlace.isNotBlank() && !lat.isNaN() && !lon.isNaN()) {
            return lat to lon
        }
        return deviceLocation(context)
    }

    /**
     * Place-name search, for the picker. Blocking; callers must be off the main thread.
     *
     * limit=3 because the picker is three rows in a dialog, and because collapsing duplicates can
     * only ever shorten that — asking for more would be paying for rows nobody sees.
     */
    fun searchPlaces(query: String, key: String): List<GeocodePlace> {
        if (query.isBlank() || key.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val body = get("$GEOCODE_URL?q=$encoded&limit=3&appid=$key") ?: return emptyList()
        return GeocodePlace.parse(body)
    }

    /**
     * Last known coarse fix, and deliberately not a fresh one.
     *
     * `requestLocationUpdates` would wake the radio for a number that only decides which city's
     * weather is shown; the last known position is accurate to far better than that. Passive first,
     * then network, then GPS — GPS last because it is the one most likely to be stale indoors and
     * the most expensive to have running.
     */
    private fun deviceLocation(context: Context): Pair<Double, Double>? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
        for (provider in providers) {
            val fix = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            if (fix != null) return fix.latitude to fix.longitude
        }
        return null
    }

    /** Body on any reply that has one, including the 4xx ones — the parser decides what it means. */
    private fun get(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
        }
        try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream?.bufferedReader()?.use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
