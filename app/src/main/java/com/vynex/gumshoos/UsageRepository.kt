package com.vynex.gumshoos

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UsageInfo(
    val lastUsed: Long,
    val foregroundMillis: Long,
)

/**
 * System-wide app usage, for "most recently opened" ordering.
 *
 * Requires PACKAGE_USAGE_STATS, which is a special access grant the user makes in Settings rather
 * than a runtime dialog. Without it every app reports zero and ordering falls back to alphabetical.
 */
object UsageRepository {

    /** 30 days back: long enough to rank rarely-used apps, short enough to stay cheap to query. */
    private const val WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000

    var stats by mutableStateOf<Map<String, UsageInfo>>(emptyMap())
        private set

    var granted by mutableStateOf(false)
        private set

    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val ok = hasPermission(context)
        if (!ok) {
            withContext(Dispatchers.Main) { granted = false; stats = emptyMap() }
            return@withContext
        }

        val manager = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val aggregated = runCatching {
            manager.queryAndAggregateUsageStats(now - WINDOW_MILLIS, now)
        }.getOrDefault(emptyMap())

        val mapped = aggregated.mapValues { (_, s) ->
            UsageInfo(lastUsed = s.lastTimeUsed, foregroundMillis = s.totalTimeInForeground)
        }

        withContext(Dispatchers.Main) {
            granted = true
            stats = mapped
        }
    }

    fun lastUsed(packageName: String): Long = stats[packageName]?.lastUsed ?: 0L

    fun foregroundMillis(packageName: String): Long = stats[packageName]?.foregroundMillis ?: 0L
}
