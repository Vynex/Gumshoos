package com.vynex.gumshoos

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.core.net.toUri
import com.vynex.gumshoos.widget.WidgetAction

internal const val GSA_PACKAGE = "com.google.android.googlequicksearchbox"
internal const val GEMINI_PACKAGE = "com.google.android.apps.bard"

/** Lens has had a standalone app. Photos was checked too, and declares no Lens activity we can name. */
internal const val LENS_PACKAGE = "com.google.ar.lens"

/**
 * One thing worth trying for a widget action.
 *
 * [intent] is null where the device doesn't have it at all — the standalone Lens app on a phone that
 * never had it. The chain drops those.
 *
 * [inChain] is what [WidgetActionActivity] actually tries, in this order. Everything else is a
 * candidate an on-device probe turned up that nothing has yet been observed doing. Those are kept
 * rather than deleted: each one is a rung already ruled out by watching a screen, and the list is
 * what stops that being re-litigated from a class name.
 */
internal data class LaunchCandidate(
    val label: String,
    val note: String,
    val intent: Intent?,
    val inChain: Boolean = false,
)

/**
 * Every entry point worth trying per action, and the chain the widget ships.
 *
 * One list, two readers. The chain is a *filter* of this list rather than a separate `when`, so a
 * rung can't be reordered in one place and left stale in the other — the tester would then be
 * reporting on a build that doesn't exist, which is worse than having no tester.
 *
 * **A rung that resolves, starts and returns success is not a rung that works.** Ten Lens entry
 * points have now been fired one at a time and watched; nine start and draw nothing. Nothing in
 * `PackageManager` distinguishes that from a real launch, which is why the tester exists and why
 * every ordering here has to be earned by someone watching the screen. The conclusion was that Lens
 * has no fallback at all rather than four bad ones — see [isAvailable].
 */
internal object WidgetActionTargets {

    /** The rungs [WidgetActionActivity] tries, in order, minus the ones this device lacks. */
    fun chain(context: Context, action: WidgetAction): List<Intent> =
        candidates(context, action).filter { it.inChain }.mapNotNull { it.intent }

    /**
     * Whether this action can do what its icon promises on this phone.
     *
     * Launchable, not merely installed. A package can be present with its launcher activity disabled
     * — uninstalling a preinstalled Lens leaves exactly that, and the probe reads it as
     * "1 of 2 declared match / internal LensLauncherActivity" rather than as absent. Asking for the
     * launch intent is the same question the chain's first rung asks, so the two cannot disagree
     * about whether the icon would work.
     *
     * An unavailable action keeps its saved on/off state. The widget skips it at render time rather
     * than switching it off, so installing the app brings the icon back exactly where it was.
     */
    fun isAvailable(context: Context, action: WidgetAction): Boolean {
        val required = action.requiredPackage ?: return true
        return launcherIntentFor(context, required) != null
    }

    fun candidates(context: Context, action: WidgetAction): List<LaunchCandidate> = when (action) {
        WidgetAction.ASSISTANT -> assistant(context)
        WidgetAction.VOICE -> voice()
        WidgetAction.LENS -> lens(context)
    }

    private fun assistant(context: Context) = listOf(
        LaunchCandidate(
            label = "VOICE_COMMAND",
            note = "Whatever holds the assistant role",
            intent = Intent(Intent.ACTION_VOICE_COMMAND),
            inChain = true,
        ),
        LaunchCandidate(
            label = "Gemini app · MAIN",
            note = "The app's own icon target, so it cannot start and vanish",
            intent = launcherIntentFor(context, GEMINI_PACKAGE),
            inChain = true,
        ),
        LaunchCandidate(
            label = "ASSIST",
            note = "Last: resolves back to us once Gumshoos holds the assistant role",
            intent = Intent(Intent.ACTION_ASSIST),
            inChain = true,
        ),
    )

    /**
     * Ordered from a probe of a real device rather than from what the names suggest.
     *
     * WEB_SEARCH, VOICE_SEARCH_HANDS_FREE and VOICE_COMMAND all resolve to the same
     * `com.google.android.voicesearch.handsfree.HandsFreeActivity`, which hands off to whichever
     * assistant is configured — so the obvious intent opens Gemini, and picking a different one of
     * those three changes nothing. SEARCH_LONG_PRESS is the only action landing somewhere else, on a
     * purpose-named entry point of its own, so it leads.
     */
    private fun voice() = listOf(
        LaunchCandidate(
            label = "SEARCH_LONG_PRESS · Google app",
            note = "The one action that lands on its own entry point rather than the hand-off",
            intent = Intent(Intent.ACTION_SEARCH_LONG_PRESS).setPackage(GSA_PACKAGE),
            inChain = true,
        ),
        LaunchCandidate(
            label = "WEB_SEARCH · Google app",
            note = "Reaches the assistant, not voice search. Kept so the icon does something",
            intent = Intent(RecognizerIntent.ACTION_WEB_SEARCH).setPackage(GSA_PACKAGE),
            inChain = true,
        ),
        LaunchCandidate(
            label = "WEB_SEARCH · anywhere",
            note = "Unscoped, for a phone without the Google app",
            intent = Intent(RecognizerIntent.ACTION_WEB_SEARCH),
            inChain = true,
        ),
        LaunchCandidate(
            label = "VOICE_SEARCH_HANDS_FREE",
            note = "Probed to the same hand-off. Listed to confirm that, not because it looks right",
            intent = Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE),
        ),
        LaunchCandidate(
            label = "RECOGNIZE_SPEECH",
            note = "Returns a transcript to the caller rather than searching; expected to be wrong",
            intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
        ),
    )

    /**
     * Settled, and not the way it was meant to be.
     *
     * Every candidate below was fired one at a time from the entry point tester on a phone without
     * the standalone app, and watched. **All of them start, return success and draw nothing** — the
     * two `MainActivity` forms, both `LensExportedActivity` forms, the two deeplinks, the share and
     * image entry points, and the internal ones throw as expected. The single exception is
     * `DirectLensYoutubeActivity`, which does open, and opens Lens pointed at YouTube rather than
     * the camera. Wrong thing, so it is not used.
     *
     * There is therefore no fallback: Lens works where the standalone app is installed and nowhere
     * else, which is why [WidgetAction.requiredPackage] exists and why the action hides itself
     * rather than shipping four rungs that quietly do nothing. The chain is one rung long.
     *
     * The rest stay listed for the tester alone. A different phone or a later Google app build may
     * behave differently, and re-deriving this list from the probe would be a day's work; re-testing
     * it is a minute's. Nothing here is dead code — it is a record of what has been ruled out, on
     * the one screen that can rule it out again.
     */
    private fun lens(context: Context) = listOf(
        LaunchCandidate(
            label = "Standalone Lens app · MAIN",
            note = "The only entry point observed opening the Lens camera",
            intent = launcherIntentFor(context, LENS_PACKAGE),
            inChain = true,
        ),

        // --- ruled out: starts, succeeds, draws nothing ------------------------------------------
        LaunchCandidate(
            label = "google://lens",
            note = "Reaches apps.lens.MainActivity. Starts, draws nothing",
            intent = Intent(Intent.ACTION_VIEW, "google://lens".toUri()).setPackage(GSA_PACKAGE),
        ),
        LaunchCandidate(
            label = "apps.lens.MainActivity · VIEW",
            note = "The same destination named directly. Starts, draws nothing",
            intent = explicit("com.google.android.apps.lens.MainActivity", Intent.ACTION_VIEW),
        ),
        LaunchCandidate(
            label = "apps.lens.MainActivity · MAIN",
            note = "Same class as an icon tap rather than a VIEW. Starts, draws nothing",
            intent = explicit("com.google.android.apps.lens.MainActivity", Intent.ACTION_MAIN),
        ),
        LaunchCandidate(
            label = "googleapp://lens",
            note = "The deeplink router. Starts, draws nothing",
            intent = Intent(Intent.ACTION_VIEW, "googleapp://lens".toUri()).setPackage(GSA_PACKAGE),
        ),
        LaunchCandidate(
            label = "LensExportedActivity · VIEW",
            note = "The original lead. Starts, draws nothing",
            intent = explicit(LENS_EXPORTED, Intent.ACTION_VIEW),
        ),
        LaunchCandidate(
            label = "LensExportedActivity · MAIN",
            note = "Starts, draws nothing",
            intent = explicit(LENS_EXPORTED, Intent.ACTION_MAIN),
        ),
        LaunchCandidate(
            label = "apps.lens.SearchImageActivity",
            note = "Exported, expects an image. Starts, draws nothing",
            intent = explicit("com.google.android.apps.lens.SearchImageActivity", Intent.ACTION_MAIN),
        ),
        LaunchCandidate(
            label = "LensShareEntryPointActivity",
            note = "Exported share target. Starts, draws nothing",
            intent = explicit(
                "com.google.android.apps.search.lens.LensShareEntryPointActivity",
                Intent.ACTION_MAIN,
            ),
        ),
        LaunchCandidate(
            label = "LensYoutubeActivity",
            note = "Exported, YouTube-specific. Starts, draws nothing",
            intent = explicit(
                "com.google.android.apps.search.lens.LensYoutubeActivity", Intent.ACTION_MAIN,
            ),
        ),
        LaunchCandidate(
            label = "apps.lens.DirectLensYoutubeActivity",
            note = "The one that opens — onto YouTube, not the camera. Rejected as the wrong thing",
            intent = explicit(
                "com.google.android.apps.lens.DirectLensYoutubeActivity", Intent.ACTION_MAIN,
            ),
        ),

        // --- not in the chain: internal, expected to throw --------------------------------------
        // A SecurityException is a *clean* negative — it says plainly that the door is shut, which is
        // more than any rung above manages. Cheap to confirm once rather than wonder about.
        LaunchCandidate(
            label = "search.lens.LensActivity (internal)",
            note = "Not exported: expect SecurityException",
            intent = explicit("com.google.android.apps.search.lens.LensActivity", Intent.ACTION_MAIN),
        ),
        LaunchCandidate(
            label = "staticplugins.lens.LensActivity (internal)",
            note = "Not exported: expect SecurityException",
            intent = explicit(
                "com.google.android.apps.gsa.staticplugins.lens.LensActivity", Intent.ACTION_MAIN,
            ),
        ),
        LaunchCandidate(
            label = "LensientActivity (internal)",
            note = "Not exported: expect SecurityException",
            intent = explicit(
                "com.google.android.apps.search.lens.LensientActivity", Intent.ACTION_MAIN,
            ),
        ),
        LaunchCandidate(
            label = "apps.lens.DirectLensientActivity (internal)",
            note = "Not exported: expect SecurityException",
            intent = explicit(
                "com.google.android.apps.lens.DirectLensientActivity", Intent.ACTION_MAIN,
            ),
        ),
        LaunchCandidate(
            label = "LensAimInputModeActivity (internal)",
            note = "Not exported: expect SecurityException",
            intent = explicit(
                "com.google.android.apps.search.lens.LensAimInputModeActivity", Intent.ACTION_MAIN,
            ),
        ),
    )

    private const val LENS_EXPORTED = "com.google.android.apps.search.lens.LensExportedActivity"

    private fun explicit(className: String, action: String): Intent =
        Intent(action).setClassName(GSA_PACKAGE, className)

    private fun launcherIntentFor(context: Context, packageName: String): Intent? =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()

    /**
     * Names a rung by what it *is*, never by its position.
     *
     * [chain] drops rungs this device lacks, so an index into it renumbers from phone to phone. A
     * trace reading "rung1" was read as the launcher intent when it was in fact the deeplink two
     * places down — on a phone without the standalone Lens app, which is precisely the case the log
     * exists to tell apart. An index is only meaningful next to the source of the build that wrote
     * it; the action and target are not.
     */
    fun describe(intent: Intent): String = buildString {
        append(intent.action?.substringAfterLast('.') ?: "no-action")
        intent.data?.let { append(' ').append(it) }
        val component = intent.component
        if (component != null) append(' ').append(component.flattenToShortString())
        else intent.`package`?.let { append(" pkg=").append(it) }
    }
}
