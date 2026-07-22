package com.vynex.gumshoos.widget

import org.json.JSONArray

/**
 * One hit from OpenWeather's geocoder, already resolved to coordinates.
 *
 * The point of keeping [lat] and [lon] rather than the name alone: a place picked once is a fixed
 * point, so every later refresh goes straight to the weather endpoint. Geocoding on each refresh
 * would double the request count for an answer that cannot change.
 */
data class GeocodePlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val country: String,
    val state: String,
) {
    /**
     * What the picker shows: "Bengaluru, Karnataka, IN".
     *
     * State and country are what make two hits distinguishable — there are Springfields in a dozen
     * US states — so a row that dropped them would offer several identical-looking choices.
     */
    val label: String
        get() = listOf(name, state, country).filter { it.isNotBlank() }.joinToString(", ")

    companion object {
        /**
         * Parses a `geo/1.0/direct` reply, or returns an empty list.
         *
         * **Duplicates are collapsed on name, state and country, keeping the first.** The API
         * genuinely returns them — the same town arrives more than once with coordinates a
         * fraction of a degree apart, from different source datasets — and to a reader those rows
         * are indistinguishable, so the picker would be asking a question with no discernible
         * answer. First wins because the API returns them best-match first.
         *
         * Empty rather than null on failure, in the same spirit as [WeatherReading.parse]: the
         * caller shows a list, and "no matches" is a state it has to render anyway.
         */
        fun parse(body: String): List<GeocodePlace> = runCatching {
            val array = JSONArray(body)
            (0 until array.length())
                .mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    if (!item.has("lat") || !item.has("lon")) return@mapNotNull null
                    val name = item.optString("name")
                    if (name.isBlank()) return@mapNotNull null
                    GeocodePlace(
                        name = name,
                        lat = item.optDouble("lat"),
                        lon = item.optDouble("lon"),
                        country = item.optString("country"),
                        state = item.optString("state"),
                    )
                }
                .filterNot { it.lat.isNaN() || it.lon.isNaN() }
                .distinctBy { Triple(it.name, it.state, it.country) }
        }.getOrDefault(emptyList())
    }
}
