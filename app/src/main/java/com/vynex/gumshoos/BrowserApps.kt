package com.vynex.gumshoos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The browsers on this phone, for the Providers screen's picker.
 *
 * Its own file rather than part of [SearchProviders] because it decodes icons: everything else there
 * is plain logic over strings and intents, and unit-testable for it.
 */
object BrowserApps {

    /** A browser that could run a web search, as the picker needs to draw it. */
    data class Browser(
        val packageName: String,
        val label: String,
        val icon: ImageBitmap?,
    )

    /**
     * A host no app can claim as its own.
     *
     * The probe has to ask what handles *the web* rather than what handles a real address, or the
     * answer is polluted by apps holding a verified link for that host — asking with a google.com
     * URL lists the Google app. `example.com` is reserved by RFC 2606 precisely so that nothing
     * owns it, so an app matching it matches the web in general, which is what a browser is.
     */
    private const val PROBE_HOST = "example.com"

    /**
     * Stops the platform narrowing a *web* query to the app it would have opened the link with.
     *
     * `queryIntentActivities` does not simply return every match for an http(s) intent: it applies
     * the same preferred-handler resolution the launch path uses, so on a phone with a default
     * browser set the answer collapses towards that one app. That is right for "who should open
     * this link" and wrong for "which browsers exist", which is the question a picker asks.
     *
     * This was the actual defect behind a phone with Chrome and Firefox listing only Firefox, and
     * the reason three separate intents all agreed: the filtering happens after the match, so no
     * amount of rephrasing the intent gets past it.
     */
    private const val MATCH_ALL = PackageManager.MATCH_ALL

    /**
     * The probes, unioned.
     *
     * Belt and braces, and **not** what fixed the missing Chrome — [MATCH_ALL] was. They are kept
     * because browsers genuinely do not declare one agreed filter, splitting http and https across
     * activities and differing on which carries `CATEGORY_BROWSABLE`, so a single intent is a bet
     * on one vendor's manifest. But no evidence here says that bet has ever been lost: all three
     * returned the same one app when the flag was wrong, which is what proved the intent was never
     * the problem.
     *
     * The host is what keeps a category-less probe honest: without `BROWSABLE` this would otherwise
     * be "every app with a deep link", and `example.com` is exactly the filter that removes them,
     * because an app claiming a specific host does not match it.
     */
    private val probes: List<Intent>
        get() = listOf(
            Intent(Intent.ACTION_VIEW, "https://$PROBE_HOST".toUri()),
            Intent(Intent.ACTION_VIEW, "http://$PROBE_HOST".toUri()),
            Intent(Intent.ACTION_VIEW, "https://$PROBE_HOST".toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE),
        )

    /**
     * Everything that can open a web page, loaded on demand — it is only needed while the settings
     * screen is up, and it decodes an icon per browser.
     *
     * The resolver activity is filtered out for the same reason the contact pickers filter it: it is
     * not an app, it is the chooser, and its icon is a generic cogwheel.
     *
     * Package visibility is not a concern here even though it usually would be: the manifest's
     * `<queries>` declares a MAIN/LAUNCHER intent, which makes every app with a launcher icon
     * visible, and a browser has one. That is worth knowing because visibility filtering never
     * fails loudly — it just returns fewer rows, which is indistinguishable from an app that
     * genuinely didn't match.
     *
     * Each probe's result is recorded. A browser missing from this list is invisible from the
     * settings screen and looks identical to one that isn't installed, so the counts are the only
     * way to tell "the probe didn't match it" from "it isn't there" without a debugger attached.
     */
    suspend fun installed(context: Context): List<Browser> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val px = (context.resources.displayMetrics.density * 32).toInt().coerceAtLeast(32)

        // What the phone would open a link with by itself. Only recorded, never used to build the
        // list: it is the one answer guaranteed to be a browser, and so the thing to compare a
        // suspicious result against.
        val web = Intent(Intent.ACTION_VIEW, "https://$PROBE_HOST".toUri())
        val default = runCatching { pm.resolveActivity(web, 0) }.getOrNull()
        EventLog.record("browsers default -> ${default?.activityInfo?.packageName ?: "none"}")

        val found = LinkedHashMap<String, ResolveInfo>()
        probes.forEach { probe ->
            val matches = runCatching { pm.queryIntentActivities(probe, MATCH_ALL) }
                .getOrDefault(emptyList())
                .filterNot { it.isResolver() }
            // Logged by what was asked, not by position: a probe list is exactly the thing someone
            // reorders later, and "probe 2 found nothing" would then mean something else.
            EventLog.record(
                "browsers ${probe.data?.scheme}${
                    if (Intent.CATEGORY_BROWSABLE in probe.categories.orEmpty()) "+browsable" else ""
                } -> ${matches.joinToString(",") { it.activityInfo.packageName }.ifEmpty { "none" }}"
            )
            matches.forEach { found.putIfAbsent(it.activityInfo.packageName, it) }
        }

        found.values
            .map { info ->
                Browser(
                    packageName = info.activityInfo.packageName,
                    label = runCatching { info.loadLabel(pm).toString() }
                        .getOrDefault(info.activityInfo.packageName),
                    icon = runCatching {
                        info.loadIcon(pm).toBitmap(px, px).asImageBitmap()
                    }.getOrNull(),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** The label for a saved package, for a row that has to name a browser it didn't enumerate. */
    fun labelFor(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()
}
