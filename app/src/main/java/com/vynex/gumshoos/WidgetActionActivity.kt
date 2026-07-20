package com.vynex.gumshoos

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.core.net.toUri
import com.vynex.gumshoos.widget.WidgetAction

/**
 * Trampoline for the search bar's action icons.
 *
 * A PendingIntent holds exactly one Intent, so `getActivity` straight to a target gives no fallback
 * and no way to find out what happened. Neither destination has a single dependable entry point —
 * Google publishes no intent for either, and most of the actions they respond to funnel into the
 * same activity, which then defers to whatever assistant is configured — so each is a short chain,
 * tried in order, with the rung that worked recorded. A silently-refused intent looks identical to
 * a working one otherwise, which is exactly the failure this project keeps running into.
 *
 * The chains below were ordered from [probeVoiceTargets] output on a real device. Three separate
 * attempts to reason out the right intent from its name were all wrong; the probe settled it in one.
 *
 * Modelled on [LaunchSearchActivity]: translucent, no history, finished before it draws. It costs a
 * launch that the overlay path deliberately avoids, but these hand off to another app where a launch
 * transition is happening anyway — and the overlay's own path is untouched by any of this.
 */
class WidgetActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = WidgetAction.fromId(intent?.getStringExtra(EXTRA_ACTION))
        if (action == null) {
            EventLog.record("widget-action unknown ${intent?.getStringExtra(EXTRA_ACTION)}")
        } else {
            run(action)
        }

        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /**
     * Walks the chain, skipping rungs nothing can handle, and records the component that won.
     *
     * Resolving first rather than relying on [startActivity] throwing matters twice over: an
     * explicit component that no longer exists is skipped rather than counted as a launch, and the
     * event trace names what actually opened. "Nothing happened" and "the wrong app opened" look
     * identical from outside, and only the resolved component tells them apart.
     */
    private fun run(action: WidgetAction) {
        chainFor(action).forEachIndexed { index, intent ->
            val target = runCatching { packageManager.resolveActivity(intent, 0) }.getOrNull()
                ?: return@forEachIndexed
            val info = target.activityInfo
            val name = "${info?.packageName}/${info?.name}"

            // The assistant chain ends in ACTION_ASSIST, which resolves back to us once Gumshoos
            // holds the assistant role — the icon would reopen the search window it was tapped
            // from. Skipping ourselves keeps the icon meaning "the other assistant" rather than
            // becoming a no-op that looks like one.
            if (info?.packageName == packageName) {
                EventLog.record("widget-action ${action.id} rung${index + 1} $name is self, skipped")
                return@forEachIndexed
            }

            val started = runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (started) {
                EventLog.record("widget-action ${action.id} -> rung${index + 1} $name")
                return
            }
            EventLog.record("widget-action ${action.id} rung${index + 1} $name refused")
        }
        EventLog.record("widget-action ${action.id} -> nothing resolved")
        Toast.makeText(this, "${action.label} isn't available", Toast.LENGTH_SHORT).show()
    }

    /**
     * Ordered fallbacks per action, strongest first.
     *
     * Every order here came from probing a real device, not from what the component names suggest.
     * Both Lens and voice search were wrong twice over precisely because a plausible-looking rung
     * reported success while doing nothing useful, hiding the working one behind it.
     */
    private fun chainFor(action: WidgetAction): List<Intent> = when (action) {
        WidgetAction.ASSISTANT -> listOfNotNull(
            Intent(Intent.ACTION_VOICE_COMMAND),
            launcherIntentFor(this, GEMINI_PACKAGE),
            Intent(Intent.ACTION_ASSIST),
        )

        // Ordered from a probe of a real device rather than from what the names suggest.
        //
        // WEB_SEARCH, VOICE_SEARCH_HANDS_FREE and VOICE_COMMAND all resolve to the same
        // com.google.android.voicesearch.handsfree.HandsFreeActivity, which hands off to whichever
        // assistant is configured — so the obvious intent opens Gemini, and picking a different one
        // of those three changes nothing. SEARCH_LONG_PRESS is the only action that lands somewhere
        // else, on a purpose-named entry point of its own, so it leads.
        WidgetAction.VOICE -> listOfNotNull(
            Intent(Intent.ACTION_SEARCH_LONG_PRESS).setPackage(GSA_PACKAGE),
            // Both known to reach the assistant on a phone with Gemini set as default. Kept only so
            // the icon does something rather than nothing where the entry point above is absent.
            Intent(RecognizerIntent.ACTION_WEB_SEARCH).setPackage(GSA_PACKAGE),
            Intent(RecognizerIntent.ACTION_WEB_SEARCH),
        )

        // Ordered by what *cannot* fail silently, rather than by what looks purpose-built.
        //
        // Twice now the chain has been led by something that resolves, starts, reports success and
        // shows nothing — LensExportedActivity, then the deeplink — each hiding every rung behind
        // it. Nothing in the API distinguishes that from a real launch, so the order can't be
        // reasoned out from names or from what resolves.
        //
        // A launcher intent can't fail that way. It is the app's own MAIN/LAUNCHER activity, the
        // thing tapping the icon runs, and Lens is installed here as a standalone package. So it
        // leads, and the entry points that can start-and-vanish come after it.
        //
        // No web fallback: lens.google.com resolves to a browser, and a browser is not what the
        // icon promises.
        WidgetAction.LENS -> listOfNotNull(
            launcherIntentFor(this, LENS_PACKAGE),
            Intent(Intent.ACTION_VIEW, "googleapp://lens".toUri()).setPackage(GSA_PACKAGE),
            Intent(Intent.ACTION_VIEW, "google://lens".toUri()).setPackage(GSA_PACKAGE),
            Intent(Intent.ACTION_VIEW).setClassName(GSA_PACKAGE, LENS_EXPORTED_ACTIVITY),
        )
    }

    private fun launcherIntentFor(context: Context, packageName: String): Intent? =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()

    companion object {
        private const val EXTRA_ACTION = "action"
        private const val GSA_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val GEMINI_PACKAGE = "com.google.android.apps.bard"

        /** Lens has had a standalone app, and Photos hosts an entry point of its own. */
        private const val LENS_PACKAGE = "com.google.ar.lens"
        private const val LENS_EXPORTED_ACTIVITY =
            "com.google.android.apps.search.lens.LensExportedActivity"
        private const val PHOTOS_PACKAGE = "com.google.android.apps.photos"

        /**
         * Writes every plausible voice-search entry point on this device to the event trace.
         *
         * Exists because the Google app's own routing is not something that can be reasoned about
         * from outside: `ACTION_WEB_SEARCH` scoped to the package resolves to `HandsFreeActivity`,
         * which then hands off to whichever assistant is configured — so the intent that looks
         * correct opens the wrong screen, and nothing about that is visible without asking the
         * device. Guessing a component name and shipping it is what this replaces, and it is what
         * eventually found SEARCH_LONG_PRESS as the one action landing anywhere else.
         */
        fun probeVoiceTargets(context: Context) {
            probe(
                context = context,
                label = "voice",
                packages = listOf(GSA_PACKAGE),
                // Matched on the class name alone. Matching the full name caught 210 of 271,
                // because the app's own package prefix is com.google.android.apps.search.* — the
                // filter let nearly everything through and the interesting entries fell past the
                // limit before anything useful appeared.
                keywords = listOf("voice", "opa", "assist", "search", "query", "entrypoint"),
                intents = listOf(
                    Intent(RecognizerIntent.ACTION_WEB_SEARCH),
                    Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE),
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                    Intent(Intent.ACTION_SEARCH_LONG_PRESS),
                    Intent(Intent.ACTION_VOICE_COMMAND),
                    Intent(Intent.ACTION_ASSIST),
                    Intent(Intent.ACTION_SEARCH),
                ),
            )
        }

        /**
         * The same, for Lens.
         *
         * Lens was shipped and then withdrawn because every component name launchers are known to
         * use either failed to resolve or opened something that closed itself again. Google
         * publishes no intent for it, so the only way to find a working entry point is to ask the
         * device what exists rather than to keep guessing class names.
         *
         * Wider than the voice probe in one respect: Lens does not necessarily live in the Google
         * app. It has had a standalone package, and Photos hosts its own Lens entry point, so all
         * three are inspected.
         */
        fun probeLensTargets(context: Context) {
            probe(
                context = context,
                label = "lens",
                packages = listOf(GSA_PACKAGE, LENS_PACKAGE, PHOTOS_PACKAGE),
                keywords = listOf("lens", "visual", "image", "camera", "scan", "capture"),
                intents = listOf(
                    Intent(Intent.ACTION_VIEW, "https://lens.google.com".toUri()),
                    Intent(Intent.ACTION_VIEW, "googleapp://lens".toUri()),
                    Intent(Intent.ACTION_VIEW, "google://lens".toUri()),
                    Intent(Intent.ACTION_SEND).setType("image/*"),
                    Intent("com.google.android.apps.lens.LENS"),
                    Intent("com.google.ar.lens.LENS"),
                ),
            )
        }

        /**
         * Reports what a set of packages declares and what a set of intents actually resolves to.
         *
         * Declared activities rather than only resolvable ones: the target may advertise no intent
         * filter matching anything we would think to try, and a component that exists but is
         * `internal` is worth knowing about too — it explains a launch that throws rather than one
         * that simply doesn't resolve.
         *
         * Both halves matter. The declared list says what could be launched; the resolution table
         * says where an intent lands, and it was the second that showed five different voice
         * actions all funnelling into the same activity.
         */
        private fun probe(
            context: Context,
            label: String,
            packages: List<String>,
            keywords: List<String>,
            intents: List<Intent>,
        ) {
            val pm = context.packageManager
            EventLog.record("$label probe: start")

            packages.forEach { pkg ->
                @Suppress("DEPRECATION")
                val info = runCatching {
                    pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                }.getOrNull()

                if (info == null) {
                    // Absent or invisible to us, and those are different problems — a missing
                    // <queries> entry looks exactly like the app not being installed.
                    EventLog.record("  $pkg: not installed or not visible")
                    return@forEach
                }

                val activities = info.activities.orEmpty()
                val matches = activities.filter { activity ->
                    val name = activity.name.orEmpty().substringAfterLast('.').lowercase()
                    keywords.any { it in name }
                }
                EventLog.record("  $pkg: ${matches.size} of ${activities.size} declared match")
                matches.take(PROBE_LIMIT).forEach {
                    EventLog.record("    ${if (it.exported) "exported" else "internal"} ${it.name}")
                }
            }

            intents.forEach { intent ->
                val open = pm.resolveActivity(intent, 0)
                val describe = intent.action + (intent.data?.let { " $it" } ?: "")
                EventLog.record(
                    "  $describe -> any:${open?.activityInfo?.packageName ?: "none"}" +
                        "/${open?.activityInfo?.name?.substringAfterLast('.') ?: ""}"
                )
                packages.forEach { pkg ->
                    val scoped = pm.resolveActivity(Intent(intent).setPackage(pkg), 0)
                    if (scoped != null) {
                        EventLog.record("      in $pkg: ${scoped.activityInfo?.name}")
                    }
                }
            }
            EventLog.record("$label probe: done")
        }

        /** Enough to identify the right activity without flooding a trace that has other uses. */
        private const val PROBE_LIMIT = 60

        /**
         * A tap target for one action.
         *
         * Carries the action in the data URI as well as an extra. PendingIntent equality ignores
         * extras, so two actions differing only by extra would collapse into one — the distinct
         * request codes already prevent that, and the URI makes the intents differ on their own
         * terms rather than relying on it.
         */
        fun pendingIntent(context: Context, action: WidgetAction): PendingIntent {
            val intent = Intent(context, WidgetActionActivity::class.java)
                .setData("gumshoos://widget-action/${action.id}".toUri())
                .putExtra(EXTRA_ACTION, action.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context,
                action.requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
