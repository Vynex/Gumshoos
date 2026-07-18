package com.vynex.gumshoos

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val label: String,
    val component: ComponentName,
    val icon: ImageBitmap,
    /** First install. Distinct from [lastUpdateTime], which also moves on every update. */
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    /**
     * Folded label plus its initials, precomputed at index time. Folding on every keystroke would
     * put Unicode normalisation in the type-ahead path for every installed app.
     */
    val indexed: AppMatcher.Indexed,
) {
    val packageName: String get() = component.packageName
}

/**
 * In-memory index of launchable apps.
 *
 * Deliberately loaded once at warm time rather than on show. Icon decoding for a full app list runs
 * into the hundreds of milliseconds, which would wreck the sub-20ms show the POC established.
 */
object AppRepository {

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set

    var loaded by mutableStateOf(false)
        private set

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val iconPx = context.resources.displayMetrics.density.times(48).toInt().coerceAtLeast(48)

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)

        val entries = resolved.mapNotNull { info ->
            runCatching {
                val activity = info.activityInfo
                val packageInfo = runCatching {
                    pm.getPackageInfo(activity.packageName, 0)
                }.getOrNull()
                val label = info.loadLabel(pm).toString()
                AppEntry(
                    label = label,
                    component = ComponentName(activity.packageName, activity.name),
                    icon = info.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap(),
                    firstInstallTime = packageInfo?.firstInstallTime ?: 0L,
                    lastUpdateTime = packageInfo?.lastUpdateTime ?: 0L,
                    indexed = AppMatcher.index(label),
                )
            }.getOrNull()
        }

        withContext(Dispatchers.Main) {
            apps = entries
            loaded = true
        }
    }

    /**
     * Ordering for a tab with no query. Recency and frequency both need usage access; without it
     * they degrade to alphabetical rather than showing an arbitrary order.
     */
    private fun baseOrder(tab: SearchTab): List<AppEntry> = when (tab) {
        SearchTab.FREQUENT ->
            if (UsageRepository.granted) {
                apps.sortedByDescending { UsageRepository.foregroundMillis(it.packageName) }
            } else {
                apps.sortedBy { it.label.lowercase() }
            }

        SearchTab.RECENT ->
            if (UsageRepository.granted) {
                apps.sortedByDescending { UsageRepository.lastUsed(it.packageName) }
            } else {
                apps.sortedBy { it.label.lowercase() }
            }

        SearchTab.NEW_UPDATED -> recentlyInstalledOrUpdated()
    }

    /**
     * Ranked results. Score dominates; most-recently-opened breaks ties, so equally good textual
     * matches surface in the order you actually use them.
     *
     * A non-empty query searches every app regardless of tab. The tabs are a way of browsing, and
     * they're hidden while searching — scoping results to one of them would silently drop matches.
     */
    fun search(query: String, tab: SearchTab): List<AppEntry> {
        if (query.isBlank()) return baseOrder(tab)

        return apps
            .mapNotNull { entry ->
                AppMatcher.score(query.trim(), entry.indexed)?.let { entry to it }
            }
            .sortedWith(
                compareByDescending<Pair<AppEntry, Int>> { it.second }
                    .thenByDescending { UsageRepository.lastUsed(it.first.packageName) }
                    .thenBy { it.first.label.lowercase() }
            )
            .map { it.first }
    }

    /**
     * Apps installed or updated inside the configured window.
     *
     * lastUpdateTime is the right field for both halves of "new/updated": a fresh install sets it
     * equal to firstInstallTime, so this catches new apps and updated ones with one comparison.
     */
    private fun recentlyInstalledOrUpdated(): List<AppEntry> {
        val cutoff = System.currentTimeMillis() -
            GumshoosConfig.newUpdatedWindowHours * 60 * 60 * 1000
        return apps
            .filter { it.lastUpdateTime >= cutoff }
            .sortedByDescending { it.lastUpdateTime }
    }

    /** System uninstall confirmation. Silently declined by the system for protected system apps. */
    fun requestUninstall(context: Context, entry: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = "package:${entry.packageName}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
    }

    fun openAppInfo(context: Context, entry: AppEntry) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${entry.packageName}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
    }

    fun launch(context: Context, entry: AppEntry) {
        EventLog.record("launch ${entry.packageName}")
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = entry.component
            // NEW_TASK because we're starting from a service context. Background-activity-start
            // restrictions don't apply: an app with a visible overlay window is exempt.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        runCatching { context.startActivity(intent) }
    }
}

enum class SearchTab(val label: String) {
    FREQUENT("FREQUENT"),
    RECENT("RECENT"),
    NEW_UPDATED("NEW/UPDATED"),
}
