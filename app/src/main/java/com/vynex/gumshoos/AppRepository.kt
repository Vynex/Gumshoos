package com.vynex.gumshoos

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val label: String,
    val component: ComponentName,
    val icon: ImageBitmap,
    val lastUpdateTime: Long,
)

/**
 * In-memory index of launchable apps.
 *
 * Deliberately loaded once at warm time rather than on show. Icon decoding for a full app list runs
 * into the hundreds of milliseconds, which would wreck the sub-20ms show the POC just established.
 * Keeping it here means the service's resident process is what makes showing cheap.
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
                val component = ComponentName(activity.packageName, activity.name)
                AppEntry(
                    label = info.loadLabel(pm).toString(),
                    component = component,
                    icon = info.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap(),
                    lastUpdateTime = runCatching {
                        pm.getPackageInfo(activity.packageName, 0).lastUpdateTime
                    }.getOrDefault(0L),
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }

        withContext(Dispatchers.Main) {
            apps = entries
            loaded = true
        }
    }

    /** Substring match on the label. Placeholder for real ranked, multi-provider search. */
    fun search(query: String, tab: SearchTab): List<AppEntry> {
        val base = when (tab) {
            // TODO: both need UsageStatsManager and the PACKAGE_USAGE_STATS permission, which is a
            // separate user grant. Alphabetical until that's wired up.
            SearchTab.FREQUENT, SearchTab.RECENT -> apps
            SearchTab.NEW_UPDATED -> apps.sortedByDescending { it.lastUpdateTime }
        }
        if (query.isBlank()) return base
        return base.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    fun launch(context: Context, entry: AppEntry) {
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
