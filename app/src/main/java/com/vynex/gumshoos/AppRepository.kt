package com.vynex.gumshoos

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
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
    /**
     * Custom names this app also answers to, indexed the same way. Empty for almost every app —
     * they exist only where someone has typed one in settings.
     */
    val aliases: List<AppMatcher.Indexed> = emptyList(),
) {
    val packageName: String get() = component.packageName

    /**
     * Settings key for this one launcher activity, computed once at index time.
     *
     * [visible] tests it against the hidden set on every access, which is the type-ahead path, so
     * this is a stored value rather than a getter — flattening per app per keystroke would put a
     * string allocation there for the same reason [indexed] exists.
     */
    val componentKey: String = component.flattenToShortString()
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
        val started = System.currentTimeMillis()
        val entries = indexPackage(context, packageName = null)
        val elapsed = System.currentTimeMillis() - started

        // Recorded rather than estimated. This is the number that decides whether reacting to a
        // package change can afford a full rebuild, and it varies enormously by how many apps are
        // installed and by screen density.
        EventLog.record("app index ${entries.size} apps in ${elapsed}ms")

        withContext(Dispatchers.Main) {
            apps = entries
            loaded = true
        }
    }

    /**
     * Builds entries for one package, or for every launchable app when [packageName] is null.
     *
     * One implementation for both so a re-indexed app can never be built differently from one that
     * came out of the initial sweep — a subtle drift there would show as an app that matches oddly
     * only until the next reboot.
     */
    private fun indexPackage(context: Context, packageName: String?): List<AppEntry> {
        val pm = context.packageManager
        val iconPx = context.resources.displayMetrics.density.times(48).toInt().coerceAtLeast(48)

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .apply { if (packageName != null) setPackage(packageName) }

        return pm.queryIntentActivities(intent, 0).mapNotNull { info ->
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
                    aliases = aliasesFor(ComponentName(activity.packageName, activity.name)),
                )
            }.getOrNull()
        }
    }

    /**
     * Re-reads one package after it was installed, updated or changed.
     *
     * Incremental on purpose. A full [load] decodes an icon for every installed app — the cost it
     * exists to keep off the show path — and a Play Store batch would trigger dozens of those in a
     * burst. This touches one package: a scoped query and an icon or two.
     *
     * Also the removal path, since a package that no longer has a launchable activity yields no
     * entries and the old ones drop out.
     */
    suspend fun reindexPackage(context: Context, packageName: String) =
        withContext(Dispatchers.IO) {
            val entries = indexPackage(context, packageName)
            withContext(Dispatchers.Main) {
                apps = apps.filterNot { it.packageName == packageName } + entries
            }
        }

    /** Drops a package outright, for an uninstall where querying it would return nothing anyway. */
    suspend fun removePackage(packageName: String) = withContext(Dispatchers.Main) {
        apps = apps.filterNot { it.packageName == packageName }
    }

    /**
     * Aliases for one launcher activity, falling back to a value stored against the whole package.
     *
     * Both settings used to be keyed by package alone. Reading the package key as a fallback is
     * what lets settings saved by those builds keep working without a migration — [GumshoosConfig]
     * loads in `GumshoosApp.onCreate`, before this index exists, so there is nowhere to expand an
     * old key into component ones at load time.
     */
    private fun aliasesFor(entry: ComponentName): List<AppMatcher.Indexed> =
        (GumshoosConfig.appAliases[entry.flattenToShortString()]
            ?: GumshoosConfig.appAliases[entry.packageName])
            ?.let { AppMatcher.indexAliases(it) }
            .orEmpty()

    /** The other launcher activities of the same app. Empty for all but a handful of packages. */
    private fun siblingsOf(entry: AppEntry): List<AppEntry> =
        apps.filter { it.packageName == entry.packageName && it.componentKey != entry.componentKey }

    /**
     * Hides or shows one launcher activity.
     *
     * A package-wide value has to be expanded onto the siblings before this component can diverge
     * from it, since the fallback in [visible] would otherwise keep applying it here: unchecking a
     * row of a package hidden as a whole would appear to do nothing at all.
     */
    fun setHidden(context: Context, entry: AppEntry, hide: Boolean) {
        if (entry.packageName in GumshoosConfig.hiddenApps) {
            GumshoosConfig.setAppHidden(context, entry.packageName, false)
            siblingsOf(entry).forEach { GumshoosConfig.setAppHidden(context, it.componentKey, true) }
        }
        GumshoosConfig.setAppHidden(context, entry.componentKey, hide)
    }

    /** Sets one launcher activity's aliases, expanding a package-wide value for the same reason. */
    fun setAliases(context: Context, entry: AppEntry, text: String) {
        GumshoosConfig.appAliases[entry.packageName]?.let { shared ->
            GumshoosConfig.setAppAliases(context, entry.packageName, "")
            siblingsOf(entry).forEach { GumshoosConfig.setAppAliases(context, it.componentKey, shared) }
        }
        GumshoosConfig.setAppAliases(context, entry.componentKey, text)
    }

    /**
     * Re-indexes aliases against the current config, without touching icons.
     *
     * Called when an alias is edited in settings. A full reload would re-decode every icon, which
     * is the hundreds of milliseconds the warm-time load exists to keep off this path.
     */
    fun reindexAliases() {
        apps = apps.map { it.copy(aliases = aliasesFor(it.component)) }
    }

    /**
     * Ordering for a tab with no query. Recency and frequency both need usage access; without it
     * they degrade to alphabetical rather than showing an arbitrary order.
     */
    /**
     * Everything except the hidden ones.
     *
     * Filtered here rather than at load, so the settings picker can still list a hidden app in
     * order to un-hide it.
     */
    private val visible: List<AppEntry>
        get() = if (GumshoosConfig.hiddenApps.isEmpty()) {
            apps
        } else {
            apps.filterNot {
                it.componentKey in GumshoosConfig.hiddenApps ||
                    it.packageName in GumshoosConfig.hiddenApps
            }
        }

    private fun baseOrder(tab: SearchTab): List<AppEntry> = when (tab) {
        // Alphabetical is the fallback whenever a signal is missing rather than an arbitrary
        // order, so an ungranted permission or an empty history still reads sensibly.
        SearchTab.FREQUENT -> visible.sortedWith(
            compareByDescending<AppEntry> { frequencyOf(it.packageName) }
                .thenBy { it.label.lowercase() }
        )

        SearchTab.RECENT -> visible.sortedWith(
            compareByDescending<AppEntry> { recencyOf(it.packageName) }
                .thenBy { it.label.lowercase() }
        )

        SearchTab.NEW_UPDATED -> recentlyInstalledOrUpdated()
    }

    /**
     * How often an app is used, from whichever source is configured.
     *
     * System usage counts foreground time, which is not quite the same question: an app left open
     * in the background all day dominates it while never being something you reach for here.
     * Gumshoos' own history counts launches through this app instead.
     */
    private fun frequencyOf(packageName: String): Long =
        when (GumshoosConfig.usageSource) {
            UsageSource.SYSTEM ->
                if (UsageRepository.granted) UsageRepository.foregroundMillis(packageName) else 0L
            UsageSource.GUMSHOOS -> LaunchHistory.countOf(packageName).toLong()
        }

    /** Most recently opened, from whichever source is configured. */
    fun recencyOf(packageName: String): Long =
        when (GumshoosConfig.usageSource) {
            UsageSource.SYSTEM ->
                if (UsageRepository.granted) UsageRepository.lastUsed(packageName) else 0L
            UsageSource.GUMSHOOS -> LaunchHistory.lastLaunchedAt(packageName)
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

        return visible
            .mapNotNull { entry ->
                AppMatcher.matchBest(
                    query.trim(),
                    entry.indexed,
                    entry.aliases,
                    GumshoosConfig.matchOptions,
                )?.let { entry to it.score }
            }
            .sortedWith(
                compareByDescending<Pair<AppEntry, Int>> { it.second }
                    .thenByDescending { recencyOf(it.first.packageName) }
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
        return visible
            .filter { it.lastUpdateTime >= cutoff }
            .sortedByDescending { it.lastUpdateTime }
    }

    /**
     * Asks the system to uninstall an app; the system shows its own confirmation.
     *
     * Needs REQUEST_DELETE_PACKAGES when targeting API 28 or later, or the intent is refused.
     * System apps cannot be uninstalled at all — the dialog will offer to disable them instead, or
     * decline outright.
     *
     * The outcome is recorded rather than swallowed: a silently discarded failure here is exactly
     * how the missing permission went unnoticed.
     */
    fun requestUninstall(context: Context, entry: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = "package:${entry.packageName}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
            .onSuccess { EventLog.record("uninstall requested ${entry.packageName}") }
            .onFailure { EventLog.record("uninstall FAILED ${it.javaClass.simpleName}") }
    }

    fun openAppInfo(context: Context, entry: AppEntry) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${entry.packageName}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Opens an app, by whichever method is configured.
     *
     * The method is a setting because of a reproducible OxygenOS misbehaviour: launch an app, go
     * Home immediately, tap the widget, and the app reopens instead of search. A trace taken on
     * 2026-07-18 showed our launch firing exactly once and the second widget tap never reaching the
     * service at all, which points at the system swallowing the tap mid-transition rather than at
     * anything here. These alternatives exist to test that conclusion rather than to assume it —
     * and [LaunchMethod.LAUNCHER_API] is the launcher-native path we were not using regardless.
     *
     * The outcome is recorded rather than swallowed, so the trace can tell a refused launch from a
     * launch the system then undid.
     */
    fun launch(context: Context, entry: AppEntry) {
        val method = GumshoosConfig.launchMethod
        LaunchHistory.record(context, entry.packageName)
        runCatching { startBy(context, entry, method) }
            .onSuccess { EventLog.record("launch ${entry.packageName} via ${method.id}") }
            .onFailure {
                EventLog.record("launch FAILED ${entry.packageName} ${it.javaClass.simpleName}")
                // Never leave a tap doing nothing because an experimental method was selected.
                if (method != LaunchMethod.STANDARD) {
                    runCatching { startBy(context, entry, LaunchMethod.STANDARD) }
                        .onSuccess { EventLog.record("launch ${entry.packageName} via fallback") }
                }
            }
    }

    private fun startBy(context: Context, entry: AppEntry, method: LaunchMethod) {
        when (method) {
            LaunchMethod.LAUNCHER_API ->
                // What a launcher is meant to use. Goes through the system's own launch path rather
                // than a plain component start, and carries the user handle, so a work-profile app
                // opens in its own profile instead of failing.
                context.getSystemService(LauncherApps::class.java)
                    .startMainActivity(entry.component, Process.myUserHandle(), null, null)

            LaunchMethod.STANDARD ->
                context.startActivity(mainIntent(entry, reset = true))

            LaunchMethod.NO_TASK_RESET ->
                context.startActivity(mainIntent(entry, reset = false))

            LaunchMethod.NO_ANIMATION ->
                context.startActivity(
                    mainIntent(entry, reset = true),
                    ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle(),
                )
        }
    }

    /**
     * [reset] adds FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, which returns an existing task to its root
     * rather than resuming it where it was left. It is on by default because that is what tapping a
     * launcher icon does; turning it off is one of the levers on the relaunch problem.
     */
    private fun mainIntent(entry: AppEntry, reset: Boolean): Intent =
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = entry.component
            // NEW_TASK because we're starting from a service context. Background-activity-start
            // restrictions don't apply: an app with a visible overlay window is exempt.
            flags = if (reset) {
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            } else {
                Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
}

/**
 * How an app is started.
 *
 * Configurable purely as a diagnostic lever. The evidence says the widget-reopens-the-last-app
 * problem is the system swallowing a tap during a Home transition, not our launch — but that
 * conclusion was reached from one trace, and this makes it cheap to test rather than argue about.
 */
enum class LaunchMethod(val id: String, val label: String, val description: String) {
    STANDARD(
        id = "standard",
        label = "Standard",
        description = "A launcher-style intent. What Gumshoos has always used.",
    ),
    LAUNCHER_API(
        id = "launcher_api",
        label = "System launcher API",
        description = "Hands off to the system's own launch path. Also the only one that opens a " +
            "work-profile app correctly.",
    ),
    NO_TASK_RESET(
        id = "no_task_reset",
        label = "Resume where left off",
        description = "Returns to the app's existing screen instead of its first one.",
    ),
    NO_ANIMATION(
        id = "no_animation",
        label = "No launch animation",
        description = "Skips the opening transition. Worth trying if the problem tracks the " +
            "animation rather than the gesture.",
    ),
    ;

    companion object {
        fun fromId(id: String?): LaunchMethod? = entries.firstOrNull { it.id == id }
    }
}

enum class SearchTab(val label: String) {
    FREQUENT("FREQUENT"),
    RECENT("RECENT"),
    NEW_UPDATED("NEW"),
}
