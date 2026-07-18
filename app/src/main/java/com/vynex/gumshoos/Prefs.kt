package com.vynex.gumshoos

import android.content.Context

/**
 * Which pre-warm strategy the overlay uses while hidden.
 *
 * The whole POC hinges on this: a view attached with its root [android.view.View.GONE] gets its
 * composition created on attach, but is never measured or laid out — so the first visible frame
 * still pays measure + layout + draw. [PREDRAWN] forces one real frame at attach time to find out
 * whether that work survives the hide/show cycle.
 */
enum class PrewarmStrategy {
    /** Attach with root GONE. Composition happens; measure/layout does not. */
    GONE,

    /** Attach fully visible but transparent and non-interactive, let one frame commit, then hide. */
    PREDRAWN;

    companion object {
        fun from(name: String?): PrewarmStrategy =
            entries.firstOrNull { it.name == name } ?: PREDRAWN
    }
}

object Prefs {
    private const val FILE = "gumshoos_prefs"
    private const val KEY_PREWARM = "prewarm_strategy"

    fun prewarmStrategy(context: Context): PrewarmStrategy =
        PrewarmStrategy.from(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY_PREWARM, null)
        )

    fun setPrewarmStrategy(context: Context, strategy: PrewarmStrategy) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREWARM, strategy.name)
            .apply()
    }
}
