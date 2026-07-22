package com.vynex.gumshoos.widget

import androidx.annotation.DrawableRes
import com.vynex.gumshoos.R
import org.json.JSONObject
import kotlin.math.roundToInt

/** Celsius or Fahrenheit, as the bar draws it. Stored by id so a rename can't lose the setting. */
enum class WeatherUnit(val id: String, val label: String, val suffix: String) {
    CELSIUS("metric", "Celsius", "°C"),
    FAHRENHEIT("imperial", "Fahrenheit", "°F"),
    ;

    companion object {
        fun fromId(id: String?): WeatherUnit = entries.firstOrNull { it.id == id } ?: CELSIUS
    }
}

/**
 * OpenWeather's condition code to the mark drawn beside the temperature.
 *
 * The codes are a small closed set — nine conditions, each with a `d` and `n` variant — so this is
 * a table rather than a parse. Only two conditions actually differ between day and night (clear sky
 * and few clouds, which swap a sun for a moon); the other seven share one drawable, because a
 * drizzle looks the same at midnight.
 *
 * **Unknown codes return null and the mark is simply omitted**, leaving the temperature on its own.
 * OpenWeather is free to add a code, and inventing a picture for one we have never seen would be a
 * guess drawn at the same size and confidence as the eight we know.
 */
object WeatherIcons {

    private val BY_CODE: Map<String, Int> = mapOf(
        "01d" to R.drawable.ic_weather_sun,
        "02d" to R.drawable.ic_weather_cloud_sun,
        "03d" to R.drawable.ic_weather_cloud,
        "04d" to R.drawable.ic_weather_cloudy,
        "09d" to R.drawable.ic_weather_cloud_drizzle,
        "10d" to R.drawable.ic_weather_cloud_rain_wind,
        "11d" to R.drawable.ic_weather_cloud_lightning,
        "13d" to R.drawable.ic_weather_cloud_snow,
        "50d" to R.drawable.ic_weather_cloud_fog,
        "01n" to R.drawable.ic_weather_moon,
        "02n" to R.drawable.ic_weather_cloud_moon,
        "03n" to R.drawable.ic_weather_cloud,
        "04n" to R.drawable.ic_weather_cloudy,
        "09n" to R.drawable.ic_weather_cloud_drizzle,
        "10n" to R.drawable.ic_weather_cloud_rain_wind,
        "11n" to R.drawable.ic_weather_cloud_lightning,
        "13n" to R.drawable.ic_weather_cloud_snow,
        "50n" to R.drawable.ic_weather_cloud_fog,
    )

    /** Every code the table answers, for the test that holds it to the documented set. */
    val codes: Set<String> get() = BY_CODE.keys

    @DrawableRes
    fun forCode(code: String?): Int? = BY_CODE[code]
}

/**
 * One current-conditions reading, as much of it as the bar and its future icons need.
 *
 * [tempC] is **always Celsius**, whatever the user picked, and the request always asks for
 * `units=metric`. Conversion happens at draw time instead, which is what makes switching between
 * C and F instant and offline: the alternative — storing whatever unit was asked for — turns a
 * display preference into a network round trip and leaves the cached reading wrong until it lands.
 *
 * [icon] is OpenWeather's condition code ("10d", "01n"). Unused today and deliberately kept: the
 * condition icons in `design/icons/weather/` map off exactly this, and it costs nothing to carry.
 */
data class WeatherReading(
    val tempC: Double,
    val icon: String,
    val description: String,
    val place: String,
) {
    /** What the bar draws, e.g. `24°C`. Rounded, because a widget has no room for a decimal. */
    fun format(unit: WeatherUnit): String = "${temperature(unit).roundToInt()}${unit.suffix}"

    fun temperature(unit: WeatherUnit): Double = when (unit) {
        WeatherUnit.CELSIUS -> tempC
        WeatherUnit.FAHRENHEIT -> tempC * 9.0 / 5.0 + 32.0
    }

    companion object {
        /**
         * Parses a `data/2.5/weather` reply, or returns null.
         *
         * **Null for anything not plainly a reading**, in the same spirit as `AnswerProvider`: this
         * feeds a widget that is otherwise showing nothing, and a wrong temperature there is worse
         * than a blank — nothing on the bar says how old or how trustworthy the number is.
         *
         * Two things about the error shapes are worth knowing, because both are silent:
         *
         * - **`cod` is sometimes a string and sometimes a number.** 200 comes back as the integer
         *   `200`; "city not found" comes back as the *string* `"404"`. `optInt` returns 0 for a
         *   string it cannot coerce, so a check of `optInt("cod") == 200` reads a 404 as "not 200"
         *   by luck rather than by design, and a check of `getInt` throws. It is read as a string
         *   and compared as one.
         * - **An error reply still has HTTP-level plausibility**: 401 for a bad key and 429 for the
         *   free tier's rate limit both arrive as well-formed JSON with no `main` block at all.
         *   Reading `main.temp` off them yields 0.0 through `optDouble`, which would draw a
         *   confident `0°C`. Hence the explicit presence checks rather than defaulted reads.
         */
        fun parse(body: String): WeatherReading? = runCatching {
            val root = JSONObject(body)

            // String comparison on purpose — see above.
            val cod = root.opt("cod")?.toString()
            if (cod != null && cod != "200") return null

            val main = root.optJSONObject("main") ?: return null
            if (!main.has("temp")) return null
            val temp = main.optDouble("temp")
            if (temp.isNaN()) return null

            // A reading with no condition block is still a temperature, which is all the bar draws
            // today, so this is optional rather than fatal.
            val weather = root.optJSONArray("weather")?.optJSONObject(0)

            WeatherReading(
                tempC = temp,
                icon = weather?.optString("icon").orEmpty(),
                description = weather?.optString("description").orEmpty(),
                place = root.optString("name"),
            )
        }.getOrNull()
    }
}
