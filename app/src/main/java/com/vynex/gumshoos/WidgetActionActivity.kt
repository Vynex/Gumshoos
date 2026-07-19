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
     * Lens used to be here and was removed: Google publishes no intent for it, and every component
     * name launchers are known to use either failed to resolve or started something that closed
     * itself immediately. A tap that silently does nothing is worse than no icon.
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
    }

    private fun launcherIntentFor(context: Context, packageName: String): Intent? =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()

    companion object {
        private const val EXTRA_ACTION = "action"
        private const val GSA_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val GEMINI_PACKAGE = "com.google.android.apps.bard"

        /**
         * Writes every plausible voice-search entry point on this device to the event trace.
         *
         * Exists because the Google app's own routing is not something that can be reasoned about
         * from outside: `ACTION_WEB_SEARCH` scoped to the package resolves to `HandsFreeActivity`,
         * which then hands off to whichever assistant is configured — so the intent that looks
         * correct opens the wrong screen, and nothing about that is visible without asking the
         * device. Guessing a component name and shipping it is what this replaces.
         *
         * Lists declared activities rather than only resolvable ones: the target may not advertise
         * an intent filter that matches anything we would think to try.
         */
        fun probeVoiceTargets(context: Context) {
            val pm = context.packageManager
            EventLog.record("voice probe: start")

            @Suppress("DEPRECATION")
            val info = runCatching {
                pm.getPackageInfo(GSA_PACKAGE, PackageManager.GET_ACTIVITIES)
            }.getOrNull()

            val activities = info?.activities.orEmpty()
            // Matched on the class name alone. Matching the full name caught 210 of 271, because
            // the app's own package prefix is com.google.android.apps.search.* — the filter let
            // nearly everything through and the interesting entries fell past the limit.
            val candidates = activities.filter {
                val name = it.name.orEmpty().substringAfterLast('.').lowercase()
                "voice" in name || "opa" in name || "assist" in name ||
                    "search" in name || "query" in name || "entrypoint" in name
            }
            EventLog.record("voice probe: ${candidates.size} of ${activities.size} declared match")
            candidates.take(PROBE_LIMIT).forEach {
                EventLog.record("  ${if (it.exported) "exported" else "internal"} ${it.name}")
            }

            // What each action actually lands on, which is the half that surprised us.
            listOf(
                RecognizerIntent.ACTION_WEB_SEARCH,
                RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE,
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
                Intent.ACTION_SEARCH_LONG_PRESS,
                Intent.ACTION_VOICE_COMMAND,
                Intent.ACTION_ASSIST,
                Intent.ACTION_SEARCH,
            ).forEach { action ->
                val scoped = pm.resolveActivity(Intent(action).setPackage(GSA_PACKAGE), 0)
                val open = pm.resolveActivity(Intent(action), 0)
                EventLog.record(
                    "  $action -> gsa:${scoped?.activityInfo?.name ?: "none"}" +
                        " | any:${open?.activityInfo?.packageName ?: "none"}"
                )
            }
            EventLog.record("voice probe: done")
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
