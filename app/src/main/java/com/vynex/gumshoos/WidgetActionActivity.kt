package com.vynex.gumshoos

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
 * The chains live in [WidgetActionTargets]; this class only walks one. Every order there was taken
 * off a real device with a probe and a one-at-a-time tester, both since removed — four separate
 * attempts to reason the right intent out from its name were all wrong, and Lens is still unsolved
 * on a phone without the standalone app. Re-derive, don't guess, if a chain ever needs changing.
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
        WidgetActionTargets.chain(this, action).forEach { intent ->
            val rung = WidgetActionTargets.describe(intent)
            val target = runCatching { packageManager.resolveActivity(intent, 0) }.getOrNull()
                ?: return@forEach
            val info = target.activityInfo
            val name = "${info?.packageName}/${info?.name}"

            // The assistant chain ends in ACTION_ASSIST, which resolves back to us once Gumshoos
            // holds the assistant role — the icon would reopen the search window it was tapped
            // from. Skipping ourselves keeps the icon meaning "the other assistant" rather than
            // becoming a no-op that looks like one.
            if (info?.packageName == packageName) {
                EventLog.record("widget-action ${action.id} [$rung] $name is self, skipped")
                return@forEach
            }

            val started = runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (started) {
                EventLog.record("widget-action ${action.id} -> [$rung] $name")
                return
            }
            EventLog.record("widget-action ${action.id} [$rung] $name refused")
        }
        EventLog.record("widget-action ${action.id} -> nothing resolved")
        Toast.makeText(this, "${action.label} isn't available", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_ACTION = "action"

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
