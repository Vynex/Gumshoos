package com.vynex.gumshoos.answers

import java.time.ZoneId

/**
 * Turning what someone types into a timezone.
 *
 * The arithmetic in [TimeAnswerProvider] is trivial; this is where the difficulty actually lives.
 * Abbreviations are genuinely ambiguous — IST is India, Israel *and* Ireland; CST is the US
 * central zone, China and Cuba — so they cannot be resolved correctly in every case. The approach
 * is to prefer the device's own country, fall back to the most populous reading, and always report
 * the resolved zone id back to the caller so a wrong guess is visible rather than silent.
 */
object ZoneAliases {

    /**
     * Abbreviations to candidate zones, most populous first. A candidate whose country matches the
     * device wins, which is what makes "IST" mean India on an Indian phone and Israel on an
     * Israeli one.
     */
    private val abbreviations: Map<String, List<String>> = mapOf(
        "utc" to listOf("UTC"),
        "gmt" to listOf("GMT"),
        "ist" to listOf("Asia/Kolkata", "Asia/Jerusalem", "Europe/Dublin"),
        "pst" to listOf("America/Los_Angeles"),
        "pdt" to listOf("America/Los_Angeles"),
        "est" to listOf("America/New_York"),
        "edt" to listOf("America/New_York"),
        "cst" to listOf("America/Chicago", "Asia/Shanghai", "America/Havana"),
        "cdt" to listOf("America/Chicago"),
        "mst" to listOf("America/Denver"),
        "mdt" to listOf("America/Denver"),
        "cet" to listOf("Europe/Paris"),
        "cest" to listOf("Europe/Paris"),
        "eet" to listOf("Europe/Athens"),
        "bst" to listOf("Europe/London"),
        "wet" to listOf("Europe/Lisbon"),
        "jst" to listOf("Asia/Tokyo"),
        "kst" to listOf("Asia/Seoul"),
        "sgt" to listOf("Asia/Singapore"),
        "hkt" to listOf("Asia/Hong_Kong"),
        "gst" to listOf("Asia/Dubai"),
        "pkt" to listOf("Asia/Karachi"),
        "bdt" to listOf("Asia/Dhaka"),
        "npt" to listOf("Asia/Kathmandu"),
        "wib" to listOf("Asia/Jakarta"),
        "pht" to listOf("Asia/Manila"),
        "msk" to listOf("Europe/Moscow"),
        "aest" to listOf("Australia/Sydney"),
        "aedt" to listOf("Australia/Sydney"),
        "awst" to listOf("Australia/Perth"),
        "acst" to listOf("Australia/Adelaide"),
        "nzst" to listOf("Pacific/Auckland"),
        "hst" to listOf("Pacific/Honolulu"),
        "akst" to listOf("America/Anchorage"),
        "brt" to listOf("America/Sao_Paulo"),
        "art" to listOf("America/Argentina/Buenos_Aires"),
        "sast" to listOf("Africa/Johannesburg"),
        "wat" to listOf("Africa/Lagos"),
        "eat" to listOf("Africa/Nairobi"),
    )

    /** Country and region names people type instead of a city. */
    private val places: Map<String, String> = mapOf(
        "india" to "Asia/Kolkata",
        "japan" to "Asia/Tokyo",
        "china" to "Asia/Shanghai",
        "singapore" to "Asia/Singapore",
        "uae" to "Asia/Dubai",
        "pakistan" to "Asia/Karachi",
        "bangladesh" to "Asia/Dhaka",
        "indonesia" to "Asia/Jakarta",
        "korea" to "Asia/Seoul",
        "thailand" to "Asia/Bangkok",
        "vietnam" to "Asia/Ho_Chi_Minh",
        "uk" to "Europe/London",
        "england" to "Europe/London",
        "britain" to "Europe/London",
        "ireland" to "Europe/Dublin",
        "france" to "Europe/Paris",
        "germany" to "Europe/Berlin",
        "spain" to "Europe/Madrid",
        "italy" to "Europe/Rome",
        "netherlands" to "Europe/Amsterdam",
        "poland" to "Europe/Warsaw",
        "russia" to "Europe/Moscow",
        "turkey" to "Europe/Istanbul",
        "israel" to "Asia/Jerusalem",
        "egypt" to "Africa/Cairo",
        "nigeria" to "Africa/Lagos",
        "kenya" to "Africa/Nairobi",
        "south africa" to "Africa/Johannesburg",
        "brazil" to "America/Sao_Paulo",
        "argentina" to "America/Argentina/Buenos_Aires",
        "mexico" to "America/Mexico_City",
        "canada" to "America/Toronto",
        "australia" to "Australia/Sydney",
        "new zealand" to "Pacific/Auckland",
        // Deliberately the eastern zone: it holds the largest share of the population, and the
        // detail line names the zone so the assumption is visible.
        "usa" to "America/New_York",
        "us" to "America/New_York",
        "america" to "America/New_York",
    )

    /**
     * Last path segment of every zone id — "Asia/Tokyo" reachable as "tokyo", "America/New_York"
     * as "new york". Built once; there are several hundred.
     */
    private val cities: Map<String, ZoneId> by lazy {
        val preferredRegions = listOf("Asia", "Europe", "America", "Australia", "Africa", "Pacific")
        ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .groupBy { it.substringAfterLast('/').replace('_', ' ').lowercase() }
            .mapValues { (_, ids) ->
                // Several regions can share a city name; prefer the more populous region so
                // resolution is at least deterministic and defensible.
                val best = ids.minByOrNull { id ->
                    val region = id.substringBefore('/')
                    preferredRegions.indexOf(region).let { if (it < 0) preferredRegions.size else it }
                }!!
                ZoneId.of(best)
            }
    }

    /**
     * Resolves [token] to a zone, or null when it means nothing — which is what stops
     * "time in spotify" producing an answer.
     */
    fun resolve(token: String, context: AnswerContext): ZoneId? {
        val normalised = token.trim().lowercase().replace('_', ' ')
        if (normalised.isEmpty()) return null

        // Exact zone id: "Asia/Tokyo".
        runCatching { return ZoneId.of(token.trim().replace(' ', '_')) }

        abbreviations[normalised]?.let { candidates ->
            return ZoneId.of(preferLocal(candidates, context))
        }
        places[normalised]?.let { return ZoneId.of(it) }
        cities[normalised]?.let { return it }
        return null
    }

    /**
     * Picks the candidate in the device's own country when there is one. Without this, an Israeli
     * phone typing IST would be told the time in India.
     */
    private fun preferLocal(candidates: List<String>, context: AnswerContext): String {
        if (candidates.size == 1) return candidates.first()
        val deviceZone = context.zoneId
        return candidates.firstOrNull { it == deviceZone } ?: candidates.first()
    }
}
