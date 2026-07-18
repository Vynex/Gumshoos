package com.vynex.gumshoos

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.concurrent.Executors

data class LaunchStat(val count: Int, val lastMillis: Long)

/**
 * Launches made through Gumshoos itself.
 *
 * An alternative to system usage stats for ordering the Frequent and Recent tabs. Two reasons it
 * earns its place: it needs no special permission at all, and it reflects how the launcher is
 * actually used rather than how the phone is — an app left open in the background all day
 * dominates system foreground time while being something you never actually reach for here.
 */
object LaunchHistory {

    private const val FILE = "gumshoos_launches"
    private const val KEY = "history"

    /** Ordered writes without holding the caller, which launches on the dismiss path. */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gumshoos-launches").apply { isDaemon = true }
    }

    var stats by mutableStateOf<Map<String, LaunchStat>>(emptyMap())
        private set

    fun load(context: Context) {
        val raw = prefs(context).getString(KEY, null) ?: return
        stats = runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { packageName ->
                    val entry = json.getJSONObject(packageName)
                    put(
                        packageName,
                        LaunchStat(entry.optInt("count"), entry.optLong("last")),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun record(context: Context, packageName: String) {
        val updated = stats.toMutableMap()
        val existing = updated[packageName]
        updated[packageName] = LaunchStat(
            count = (existing?.count ?: 0) + 1,
            lastMillis = System.currentTimeMillis(),
        )
        stats = updated

        val snapshot = updated
        val appContext = context.applicationContext
        writer.execute {
            runCatching {
                val json = JSONObject()
                snapshot.forEach { (packageName, stat) ->
                    json.put(
                        packageName,
                        JSONObject().put("count", stat.count).put("last", stat.lastMillis),
                    )
                }
                prefs(appContext).edit().putString(KEY, json.toString()).apply()
            }
        }
    }

    fun countOf(packageName: String): Int = stats[packageName]?.count ?: 0

    fun lastLaunchedAt(packageName: String): Long = stats[packageName]?.lastMillis ?: 0L

    fun clear(context: Context) {
        stats = emptyMap()
        prefs(context).edit().remove(KEY).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/** Where the Frequent and Recent tabs get their ordering from. */
enum class UsageSource {
    /** System-wide foreground time and last-used, via PACKAGE_USAGE_STATS. */
    SYSTEM,

    /** Only launches made from this app. Needs no permission. */
    GUMSHOOS,
}
