package com.vynex.gumshoos

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vynex.gumshoos.answers.AnswerProviders
import com.vynex.gumshoos.answers.MeasurementSystem
import com.vynex.gumshoos.widget.WidgetAction
import com.vynex.gumshoos.widget.WidgetBarFill
import com.vynex.gumshoos.widget.WidgetBarLogo
import com.vynex.gumshoos.widget.WidgetBarMetrics
import com.vynex.gumshoos.widget.WidgetBarStyle

/**
 * Tunables that will eventually be exposed in a settings screen. Persisted now so that screen only
 * has to render them, not migrate anything.
 */
object GumshoosConfig {
    private const val FILE = "gumshoos_config"
    private const val KEY_NEW_UPDATED_WINDOW_HOURS = "new_updated_window_hours"
    private const val KEY_WIDGET_ICON_SIZE = "widget_icon_size_dp"
    private const val KEY_WIDGET_ICON_OFFSET = "widget_icon_offset_dp"
    private const val KEY_CONTACT_ACTION_PACKAGE = "contact_action_package_"
    private const val KEY_HIDE_ON_FOCUS_LOSS = "hide_on_focus_loss"
    private const val KEY_USAGE_SOURCE = "usage_source"
    private const val KEY_UNIT_SYSTEM = "unit_system"
    private const val KEY_PREFERRED_CURRENCY = "preferred_currency"
    private const val KEY_PREFERRED_ZONE = "preferred_zone"
    private const val KEY_ANIMATION_MODE = "animation_mode"
    private const val KEY_ANSWER_PROVIDERS = "answer_providers_enabled"
    private const val KEY_HIDDEN_APPS = "hidden_apps"
    private const val KEY_HIDDEN_CONTACTS = "hidden_contacts"
    private const val KEY_APP_ALIASES = "app_aliases_"
    private const val KEY_MATCH_TIERS = "match_tiers"
    private const val KEY_DISABLED_PROVIDERS = "disabled_providers"
    private const val KEY_PROVIDER_ORDER = "provider_order"
    private const val KEY_SETUP_COMPLETE = "setup_complete"
    private const val KEY_WIDGET_BAR_FILL_MODE = "widget_bar_fill_mode"
    private const val KEY_WIDGET_BAR_FILL_COLOR = "widget_bar_fill_color"
    private const val KEY_WIDGET_BAR_FILL_ALPHA = "widget_bar_fill_alpha"
    private const val KEY_WIDGET_BAR_WIREFRAME = "widget_bar_wireframe"
    private const val KEY_WIDGET_BAR_CORNER = "widget_bar_corner"
    private const val KEY_WIDGET_BAR_SHADOW = "widget_bar_shadow"
    private const val KEY_WIDGET_BAR_SHOW_HINT = "widget_bar_show_hint"
    private const val KEY_WIDGET_BAR_LOGO = "widget_bar_logo"
    private const val KEY_WIDGET_BAR_ACTIONS = "widget_bar_actions_enabled"
    private const val KEY_WIDGET_BAR_ACTION_ORDER = "widget_bar_action_order"
    private const val KEY_WIDGET_BAR_PAD_H = "widget_bar_padding_h"
    private const val KEY_WIDGET_BAR_PAD_V = "widget_bar_padding_v"
    private const val KEY_LAUNCH_METHOD = "launch_method"
    private const val KEY_WINDOW_ADAPTIVE = "search_window_adaptive"
    private const val KEY_WINDOW_COLOR = "search_window_color"
    private const val KEY_WINDOW_ALPHA = "search_window_alpha"
    private const val KEY_WINDOW_CORNER = "search_window_corner"
    private const val KEY_GRID_ROWS = "overlay_grid_rows"
    private const val KEY_ICON_SIZE = "overlay_icon_size"
    private const val KEY_LABEL_SIZE = "overlay_label_size"

    const val DEFAULT_NEW_UPDATED_WINDOW_HOURS = 12L

    /**
     * Whether losing window focus dismisses the overlay. On by default: without it the card is left
     * sitting above whatever a tapped notification opened. The cost is that Home dismissal comes
     * along with it, reporting late enough to feel sluggish, since focus loss can't say what took
     * over.
     */
    const val DEFAULT_HIDE_ON_FOCUS_LOSS = true

    /** System usage by default, since it already knows about apps opened before Gumshoos existed. */
    val DEFAULT_USAGE_SOURCE = UsageSource.SYSTEM

    /** Matches the measured diameter of a real launcher icon on the reference device. */
    const val DEFAULT_WIDGET_ICON_SIZE_DP = 46

    /**
     * Vertical nudge from the centre of the launcher cell, in dp. Positive moves it down.
     * Zero centres it, which is where most launchers draw an app icon; some sit slightly lower.
     */
    const val DEFAULT_WIDGET_ICON_OFFSET_DP = 0

    /** Full width, slightly inset vertically — the bar sits in its row rather than filling it. */
    /**
     * The search window's shipped look, matching what it was before any of it was configurable.
     *
     * Kept as a percentage rather than a boolean even though only two values are offered, so which
     * two stays a product decision rather than something baked into storage.
     */
    const val WINDOW_ALPHA_TRANSPARENT = 94
    const val WINDOW_ALPHA_OPAQUE = 100
    const val DEFAULT_WINDOW_ALPHA = WINDOW_ALPHA_TRANSPARENT
    const val DEFAULT_WINDOW_CORNER_DP = 20

    /**
     * Grid defaults, which are the measured ones the card's geometry was built around.
     *
     * The icon range is bounded by the five columns: at a card of roughly 360dp of usable width a
     * cell is about 72dp, so anything past the mid-fifties starts crowding its neighbours.
     */
    const val DEFAULT_GRID_ROWS = 2
    const val MIN_GRID_ROWS = 1
    const val MAX_GRID_ROWS = 3

    const val DEFAULT_ICON_SIZE_DP = 42
    const val MIN_ICON_SIZE_DP = 30
    const val MAX_ICON_SIZE_DP = 56

    const val DEFAULT_LABEL_SIZE_SP = 12
    const val MIN_LABEL_SIZE_SP = 9
    const val MAX_LABEL_SIZE_SP = 16

    /** Only used until something is picked; adaptive is the shipped default. */
    const val DEFAULT_WIDGET_BAR_COLOR = 0xFFFFFFFF.toInt()

    const val DEFAULT_WIDGET_BAR_PAD_H = 0
    const val DEFAULT_WIDGET_BAR_PAD_V = 8

    /** How recently an app must have been installed or updated to show under NEW/UPDATED. */
    var newUpdatedWindowHours by mutableStateOf(DEFAULT_NEW_UPDATED_WINDOW_HOURS)
        private set

    /** Drawn diameter of the app icon widget's icon, in dp. */
    var widgetIconSizeDp by mutableStateOf(DEFAULT_WIDGET_ICON_SIZE_DP)
        private set

    /** Vertical offset from the centre of the cell, in dp; positive is downward. */
    var widgetIconOffsetDp by mutableStateOf(DEFAULT_WIDGET_ICON_OFFSET_DP)
        private set

    /**
     * Chosen handler per contact action, or null to let the system decide. Keyed by action so the
     * dialer and SMS app can be exposed the same way later; only email is surfaced today, since
     * that's the one where a missing default produces the resolver's cogwheel.
     */
    var contactActionPackages by mutableStateOf<Map<ContactAction, String>>(emptyMap())
        private set

    var hideOnFocusLoss by mutableStateOf(DEFAULT_HIDE_ON_FOCUS_LOSS)
        private set

    /**
     * Overrides for what the device would otherwise imply. Null means follow the device, which is
     * the existing behaviour and stays the default — an Indian phone keeps answering 5ft in metres
     * and $5 in rupees without anyone choosing anything.
     */
    var unitSystemOverride by mutableStateOf<MeasurementSystem?>(null)
        private set

    var preferredCurrency by mutableStateOf<String?>(null)
        private set

    var preferredZone by mutableStateOf<String?>(null)
        private set

    var animationMode by mutableStateOf(AnimationMode.FOLLOW_SYSTEM)
        private set

    fun setUnitSystemOverride(context: Context, system: MeasurementSystem?) {
        unitSystemOverride = system
        putOrRemove(context, KEY_UNIT_SYSTEM, system?.name)
    }

    fun setPreferredCurrency(context: Context, code: String?) {
        preferredCurrency = code
        putOrRemove(context, KEY_PREFERRED_CURRENCY, code)
    }

    fun setPreferredZone(context: Context, zoneId: String?) {
        preferredZone = zoneId
        putOrRemove(context, KEY_PREFERRED_ZONE, zoneId)
    }

    fun setAnimationMode(context: Context, mode: AnimationMode) {
        animationMode = mode
        prefs(context).edit().putString(KEY_ANIMATION_MODE, mode.name).apply()
    }

    /**
     * Whether the overlay should animate.
     *
     * Following the system is the default so anyone who has already turned animations off
     * phone-wide gets that here without having to find this setting too.
     */
    fun animationsEnabled(context: Context): Boolean = when (animationMode) {
        AnimationMode.ALWAYS_OFF -> false
        AnimationMode.ALWAYS_ON -> true
        AnimationMode.FOLLOW_SYSTEM -> systemAnimationScale(context) != 0f
    }

    fun systemAnimationScale(context: Context): Float = runCatching {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)

    /** Which instant-answer modules are on. Pushed into AnswerProviders, which owns the dispatch. */
    fun setAnswerProvidersEnabled(context: Context, ids: Set<String>) {
        AnswerProviders.enabledIds = ids
        prefs(context).edit().putStringSet(KEY_ANSWER_PROVIDERS, ids).apply()
    }

    private fun putOrRemove(context: Context, key: String, value: String?) {
        prefs(context).edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    /** Packages excluded from results entirely — search, tabs and all. */
    var hiddenApps by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Contacts excluded from results, keyed on LOOKUP_KEY rather than _ID.
     *
     * Row ids are reassigned when an account re-syncs, so a blacklist keyed on them would quietly
     * start hiding the wrong people, or nobody at all. The lookup key survives that.
     */
    var hiddenContacts by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Extra names an app answers to, as typed — "insta, ig" — keyed by package.
     *
     * Kept as the raw text rather than a parsed list so the settings field can show back exactly
     * what was entered. [AppRepository] owns the indexed form.
     */
    var appAliases by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /**
     * Which optional match tiers are live. Everything on by default, which is how matching behaved
     * before it was configurable.
     */
    var matchOptions by mutableStateOf(AppMatcher.MatchOptions.ALL)
        private set

    fun setAppAliases(context: Context, packageName: String, text: String) {
        val trimmed = text.trim()
        appAliases = if (trimmed.isEmpty()) {
            appAliases - packageName
        } else {
            appAliases + (packageName to trimmed)
        }
        putOrRemove(context, KEY_APP_ALIASES + packageName, trimmed.ifEmpty { null })
        // Aliases are indexed once and matched on every keystroke, so the index is rebuilt here
        // rather than folded on the type-ahead path. Same reason AnswerProviders is pushed to.
        AppRepository.reindexAliases()
    }

    fun setMatchTierEnabled(context: Context, tier: AppMatcher.MatchTier, enabled: Boolean) {
        val tiers = if (enabled) {
            matchOptions.enabled + tier
        } else {
            matchOptions.enabled - tier
        }
        matchOptions = AppMatcher.MatchOptions(tiers)
        prefs(context).edit().putStringSet(KEY_MATCH_TIERS, tiers.map { it.id }.toSet()).apply()
    }

    // --- wide search bar widget ------------------------------------------------------------------
    //
    // Defaults reproduce the bar this widget is modelled on: a pill, filled with the wallpaper's
    // neutral surface, shadowed, Google mark on the left, no hint text, voice and lens on the right.

    /**
     * Whether the bar follows the system theme, and the colour to use when it doesn't.
     *
     * Held as two independent values rather than as one sealed [WidgetBarFill]. The sealed form
     * only carried a colour in its Solid case, so switching to Adaptive discarded the chosen one
     * and switching back produced whatever Adaptive happened to resolve to — the pick was lost by
     * a round trip through the toggle. Keeping them apart means the colour is simply not in play
     * while Adaptive is on, rather than gone.
     */
    var widgetBarFillAdaptive by mutableStateOf(true)
        private set

    var widgetBarSolidColor by mutableStateOf(DEFAULT_WIDGET_BAR_COLOR)
        private set

    /** The pair as the renderer wants it. */
    val widgetBarFill: WidgetBarFill
        get() = if (widgetBarFillAdaptive) {
            WidgetBarFill.Adaptive
        } else {
            WidgetBarFill.Solid(widgetBarSolidColor)
        }

    /** 0..100, kept apart from the colour so changing one doesn't reset the other. */
    var widgetBarFillAlpha by mutableStateOf(100)
        private set

    var widgetBarWireframe by mutableStateOf(false)
        private set

    /** 0 = square, 100 = pill. A fraction of the bar's height, since the bar is resizable. */
    var widgetBarCorner by mutableStateOf(100)
        private set

    var widgetBarShadow by mutableStateOf(true)
        private set

    /**
     * Gap between the bar and its cell, in dp.
     *
     * Horizontal defaults to nothing so the bar spans the full cell; vertical to a small inset,
     * which is what makes it read as a bar sitting in the row rather than filling it. Independent
     * of [widgetBarShadow] — the shadow draws into this margin instead of creating its own.
     */
    var widgetBarPaddingH by mutableStateOf(DEFAULT_WIDGET_BAR_PAD_H)
        private set

    var widgetBarPaddingV by mutableStateOf(DEFAULT_WIDGET_BAR_PAD_V)
        private set

    var widgetBarShowHint by mutableStateOf(false)
        private set

    var widgetBarLogo by mutableStateOf(WidgetBarLogo.GOOGLE)
        private set

    /**
     * Action icons switched **on**, by id.
     *
     * The inverse of [disabledProviders], deliberately. That stores the off-set so a provider added
     * later defaults on; here there are only three slots in the layout, so an action added later
     * must not push itself into a bar someone has already arranged.
     */
    var widgetBarActions by mutableStateOf(WidgetAction.DEFAULT_ENABLED)
        private set

    /** Action ids in display order; unlisted ids sort last, as with [providerOrder]. */
    var widgetBarActionOrder by mutableStateOf<List<String>>(emptyList())
        private set

    /** Everything the renderer needs, in one value. */
    val widgetBarStyle: WidgetBarStyle
        get() = WidgetBarStyle(
            fill = widgetBarFill,
            fillAlphaPercent = widgetBarFillAlpha,
            wireframe = widgetBarWireframe,
            cornerPercent = widgetBarCorner,
            shadow = widgetBarShadow,
            showHint = widgetBarShowHint,
            logo = widgetBarLogo,
            paddingHDp = widgetBarPaddingH,
            paddingVDp = widgetBarPaddingV,
        )

    fun setWidgetBarPaddingH(context: Context, dp: Int) {
        widgetBarPaddingH = dp.coerceIn(0, WidgetBarMetrics.MAX_PADDING_H_DP)
        prefs(context).edit().putInt(KEY_WIDGET_BAR_PAD_H, widgetBarPaddingH).apply()
    }

    fun setWidgetBarPaddingV(context: Context, dp: Int) {
        widgetBarPaddingV = dp.coerceIn(0, WidgetBarMetrics.MAX_PADDING_V_DP)
        prefs(context).edit().putInt(KEY_WIDGET_BAR_PAD_V, widgetBarPaddingV).apply()
    }

    fun setWidgetBarFillAdaptive(context: Context, adaptive: Boolean) {
        widgetBarFillAdaptive = adaptive
        val mode = if (adaptive) WidgetBarFill.ADAPTIVE_ID else WidgetBarFill.SOLID_ID
        // The colour key is left alone, so turning this back off restores what was chosen before.
        prefs(context).edit().putString(KEY_WIDGET_BAR_FILL_MODE, mode).apply()
    }

    /** Choosing a colour is itself a statement that the bar shouldn't follow the system. */
    fun setWidgetBarSolidColor(context: Context, color: Int) {
        widgetBarSolidColor = color
        widgetBarFillAdaptive = false
        prefs(context).edit()
            .putInt(KEY_WIDGET_BAR_FILL_COLOR, color)
            .putString(KEY_WIDGET_BAR_FILL_MODE, WidgetBarFill.SOLID_ID)
            .apply()
    }

    fun setWidgetBarFillAlpha(context: Context, percent: Int) {
        widgetBarFillAlpha = percent.coerceIn(0, 100)
        prefs(context).edit().putInt(KEY_WIDGET_BAR_FILL_ALPHA, widgetBarFillAlpha).apply()
    }

    fun setWidgetBarWireframe(context: Context, enabled: Boolean) {
        widgetBarWireframe = enabled
        prefs(context).edit().putBoolean(KEY_WIDGET_BAR_WIREFRAME, enabled).apply()
    }

    fun setWidgetBarCorner(context: Context, percent: Int) {
        widgetBarCorner = percent.coerceIn(0, 100)
        prefs(context).edit().putInt(KEY_WIDGET_BAR_CORNER, widgetBarCorner).apply()
    }

    fun setWidgetBarShadow(context: Context, enabled: Boolean) {
        widgetBarShadow = enabled
        prefs(context).edit().putBoolean(KEY_WIDGET_BAR_SHADOW, enabled).apply()
    }

    fun setWidgetBarShowHint(context: Context, show: Boolean) {
        widgetBarShowHint = show
        prefs(context).edit().putBoolean(KEY_WIDGET_BAR_SHOW_HINT, show).apply()
    }

    fun setWidgetBarLogo(context: Context, logo: WidgetBarLogo) {
        widgetBarLogo = logo
        prefs(context).edit().putString(KEY_WIDGET_BAR_LOGO, logo.id).apply()
    }

    fun setWidgetBarActionEnabled(context: Context, id: String, enabled: Boolean) {
        widgetBarActions = if (enabled) widgetBarActions + id else widgetBarActions - id
        prefs(context).edit().putStringSet(KEY_WIDGET_BAR_ACTIONS, widgetBarActions).apply()
    }

    /**
     * Back to the shipped look.
     *
     * Clears the keys rather than writing the defaults over them, so the defaults stay defined in
     * exactly one place — [load] — and can't drift into a second copy here.
     */
    fun resetWidgetBar(context: Context) {
        prefs(context).edit().apply {
            remove(KEY_WIDGET_BAR_FILL_MODE)
            remove(KEY_WIDGET_BAR_FILL_COLOR)
            remove(KEY_WIDGET_BAR_FILL_ALPHA)
            remove(KEY_WIDGET_BAR_WIREFRAME)
            remove(KEY_WIDGET_BAR_CORNER)
            remove(KEY_WIDGET_BAR_SHADOW)
            remove(KEY_WIDGET_BAR_SHOW_HINT)
            remove(KEY_WIDGET_BAR_LOGO)
            remove(KEY_WIDGET_BAR_ACTIONS)
            remove(KEY_WIDGET_BAR_ACTION_ORDER)
            remove(KEY_WIDGET_BAR_PAD_H)
            remove(KEY_WIDGET_BAR_PAD_V)
        }.apply()
        load(context)
    }

    fun setWidgetBarActionOrder(context: Context, ids: List<String>) {
        widgetBarActionOrder = ids
        prefs(context).edit()
            .putString(KEY_WIDGET_BAR_ACTION_ORDER, ids.joinToString(","))
            .apply()
    }

    /**
     * Whether the setup screen has been through once.
     *
     * Only records that the optional permissions have been *explained*, not that they were granted
     * — refusing contacts access is a legitimate way to finish setup.
     */
    var setupComplete by mutableStateOf(false)
        private set

    fun setSetupComplete(context: Context) {
        setupComplete = true
        prefs(context).edit().putBoolean(KEY_SETUP_COMPLETE, true).apply()
    }

    /**
     * Search providers switched off, by id.
     *
     * The off set rather than the on set, so a provider added in a later version is on by default
     * rather than invisible to everyone who already has settings saved.
     */
    var disabledProviders by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Provider ids in display order. Empty until something is reordered, which means declaration
     * order; ids missing from it sort to the end. See [SearchProviders.inConfiguredOrder].
     */
    var providerOrder by mutableStateOf<List<String>>(emptyList())
        private set

    fun setProviderEnabled(context: Context, id: String, enabled: Boolean) {
        disabledProviders = if (enabled) disabledProviders - id else disabledProviders + id
        prefs(context).edit().putStringSet(KEY_DISABLED_PROVIDERS, disabledProviders).apply()
    }

    fun setProviderOrder(context: Context, ids: List<String>) {
        providerOrder = ids
        // Joined rather than a string set: SharedPreferences sets are unordered, and order is the
        // entire content of this value. Ids are slugs, so the separator can't appear inside one.
        prefs(context).edit().putString(KEY_PROVIDER_ORDER, ids.joinToString(",")).apply()
    }

    fun setAppHidden(context: Context, packageName: String, hidden: Boolean) {
        hiddenApps = if (hidden) hiddenApps + packageName else hiddenApps - packageName
        prefs(context).edit().putStringSet(KEY_HIDDEN_APPS, hiddenApps).apply()
    }

    fun setContactHidden(context: Context, lookupKey: String, hidden: Boolean) {
        hiddenContacts = if (hidden) hiddenContacts + lookupKey else hiddenContacts - lookupKey
        prefs(context).edit().putStringSet(KEY_HIDDEN_CONTACTS, hiddenContacts).apply()
    }

    /**
     * How an app is opened from search.
     *
     * A diagnostic lever rather than a preference — see [LaunchMethod]. Standard is what the app
     * has always done, so leaving it alone changes nothing.
     */
    var launchMethod by mutableStateOf(LaunchMethod.STANDARD)
        private set

    fun setLaunchMethod(context: Context, method: LaunchMethod) {
        launchMethod = method
        prefs(context).edit().putString(KEY_LAUNCH_METHOD, method.id).apply()
    }

    // --- the search window itself -----------------------------------------------------------------
    //
    // Adaptive and the chosen colour are kept apart for the same reason as the widget bar's: folding
    // them into one value loses the pick the moment adaptive is switched on.

    var searchWindowAdaptive by mutableStateOf(true)
        private set

    var searchWindowColor by mutableStateOf(DEFAULT_WIDGET_BAR_COLOR)
        private set

    var searchWindowAlpha by mutableStateOf(DEFAULT_WINDOW_ALPHA)
        private set

    var searchWindowCorner by mutableStateOf(DEFAULT_WINDOW_CORNER_DP)
        private set

    val searchWindowStyle: SearchWindowStyle
        get() = SearchWindowStyle(
            adaptive = searchWindowAdaptive,
            color = searchWindowColor,
            alphaPercent = searchWindowAlpha,
            cornerRadiusDp = searchWindowCorner,
        )

    fun setSearchWindowAdaptive(context: Context, adaptive: Boolean) {
        searchWindowAdaptive = adaptive
        // The colour key is left alone, so turning this back off restores what was chosen before.
        prefs(context).edit().putBoolean(KEY_WINDOW_ADAPTIVE, adaptive).apply()
    }

    /** Choosing a colour is itself a statement that the window shouldn't follow the theme. */
    fun setSearchWindowColor(context: Context, color: Int) {
        searchWindowColor = color
        searchWindowAdaptive = false
        prefs(context).edit()
            .putInt(KEY_WINDOW_COLOR, color)
            .putBoolean(KEY_WINDOW_ADAPTIVE, false)
            .apply()
    }

    fun setSearchWindowAlpha(context: Context, percent: Int) {
        searchWindowAlpha = percent.coerceIn(0, 100)
        prefs(context).edit().putInt(KEY_WINDOW_ALPHA, searchWindowAlpha).apply()
    }

    fun setSearchWindowCorner(context: Context, dp: Int) {
        searchWindowCorner = dp.coerceIn(0, SEARCH_WINDOW_MAX_CORNER_DP)
        prefs(context).edit().putInt(KEY_WINDOW_CORNER, searchWindowCorner).apply()
    }

    // --- the app grid inside the window ------------------------------------------------------------

    var overlayGridRows by mutableStateOf(DEFAULT_GRID_ROWS)
        private set

    var overlayIconSize by mutableStateOf(DEFAULT_ICON_SIZE_DP)
        private set

    var overlayLabelSize by mutableStateOf(DEFAULT_LABEL_SIZE_SP)
        private set

    fun setOverlayGridRows(context: Context, rows: Int) {
        overlayGridRows = rows.coerceIn(MIN_GRID_ROWS, MAX_GRID_ROWS)
        prefs(context).edit().putInt(KEY_GRID_ROWS, overlayGridRows).apply()
    }

    fun setOverlayIconSize(context: Context, dp: Int) {
        overlayIconSize = dp.coerceIn(MIN_ICON_SIZE_DP, MAX_ICON_SIZE_DP)
        prefs(context).edit().putInt(KEY_ICON_SIZE, overlayIconSize).apply()
    }

    fun setOverlayLabelSize(context: Context, sp: Int) {
        overlayLabelSize = sp.coerceIn(MIN_LABEL_SIZE_SP, MAX_LABEL_SIZE_SP)
        prefs(context).edit().putInt(KEY_LABEL_SIZE, overlayLabelSize).apply()
    }

    fun resetSearchWindow(context: Context) {
        prefs(context).edit().apply {
            remove(KEY_WINDOW_ADAPTIVE)
            remove(KEY_WINDOW_COLOR)
            remove(KEY_WINDOW_ALPHA)
            remove(KEY_WINDOW_CORNER)
            remove(KEY_GRID_ROWS)
            remove(KEY_ICON_SIZE)
            remove(KEY_LABEL_SIZE)
        }.apply()
        load(context)
    }

    /** Where the Frequent and Recent tabs take their ordering from. */
    var usageSource by mutableStateOf(DEFAULT_USAGE_SOURCE)
        private set

    fun setUsageSource(context: Context, source: UsageSource) {
        usageSource = source
        prefs(context).edit().putString(KEY_USAGE_SOURCE, source.name).apply()
    }

    fun setHideOnFocusLoss(context: Context, enabled: Boolean) {
        hideOnFocusLoss = enabled
        prefs(context).edit().putBoolean(KEY_HIDE_ON_FOCUS_LOSS, enabled).apply()
    }

    fun setContactActionPackage(context: Context, action: ContactAction, packageName: String?) {
        contactActionPackages = contactActionPackages.toMutableMap().apply {
            if (packageName == null) remove(action) else put(action, packageName)
        }
        prefs(context).edit().apply {
            if (packageName == null) {
                remove(KEY_CONTACT_ACTION_PACKAGE + action.name)
            } else {
                putString(KEY_CONTACT_ACTION_PACKAGE + action.name, packageName)
            }
        }.apply()
    }

    fun load(context: Context) {
        val p = prefs(context)
        contactActionPackages = ContactAction.entries.mapNotNull { action ->
            p.getString(KEY_CONTACT_ACTION_PACKAGE + action.name, null)?.let { action to it }
        }.toMap()
        newUpdatedWindowHours =
            p.getLong(KEY_NEW_UPDATED_WINDOW_HOURS, DEFAULT_NEW_UPDATED_WINDOW_HOURS)
        widgetIconSizeDp = p.getInt(KEY_WIDGET_ICON_SIZE, DEFAULT_WIDGET_ICON_SIZE_DP)
        widgetIconOffsetDp = p.getInt(KEY_WIDGET_ICON_OFFSET, DEFAULT_WIDGET_ICON_OFFSET_DP)
        hideOnFocusLoss = p.getBoolean(KEY_HIDE_ON_FOCUS_LOSS, DEFAULT_HIDE_ON_FOCUS_LOSS)
        // Copied: the set from SharedPreferences must not be mutated or read after the fact.
        hiddenApps = p.getStringSet(KEY_HIDDEN_APPS, null)?.toSet() ?: emptySet()
        hiddenContacts = p.getStringSet(KEY_HIDDEN_CONTACTS, null)?.toSet() ?: emptySet()

        // One key per package, so the map is recovered by scanning rather than parsing a blob.
        appAliases = p.all
            .filterKeys { it.startsWith(KEY_APP_ALIASES) }
            .mapNotNull { (key, value) ->
                (value as? String)?.takeIf { it.isNotBlank() }
                    ?.let { key.removePrefix(KEY_APP_ALIASES) to it }
            }
            .toMap()
        setupComplete = p.getBoolean(KEY_SETUP_COMPLETE, false)

        widgetBarFillAdaptive =
            p.getString(KEY_WIDGET_BAR_FILL_MODE, null) != WidgetBarFill.SOLID_ID
        widgetBarSolidColor = p.getInt(KEY_WIDGET_BAR_FILL_COLOR, DEFAULT_WIDGET_BAR_COLOR)
        widgetBarFillAlpha = p.getInt(KEY_WIDGET_BAR_FILL_ALPHA, 100).coerceIn(0, 100)
        widgetBarWireframe = p.getBoolean(KEY_WIDGET_BAR_WIREFRAME, false)
        widgetBarCorner = p.getInt(KEY_WIDGET_BAR_CORNER, 100).coerceIn(0, 100)
        widgetBarShadow = p.getBoolean(KEY_WIDGET_BAR_SHADOW, true)
        widgetBarShowHint = p.getBoolean(KEY_WIDGET_BAR_SHOW_HINT, false)
        widgetBarLogo = WidgetBarLogo.fromId(p.getString(KEY_WIDGET_BAR_LOGO, null))
            ?: WidgetBarLogo.GOOGLE
        widgetBarActions = p.getStringSet(KEY_WIDGET_BAR_ACTIONS, null)?.toSet()
            ?: WidgetAction.DEFAULT_ENABLED
        widgetBarPaddingH = p.getInt(KEY_WIDGET_BAR_PAD_H, DEFAULT_WIDGET_BAR_PAD_H)
            .coerceIn(0, WidgetBarMetrics.MAX_PADDING_H_DP)
        widgetBarPaddingV = p.getInt(KEY_WIDGET_BAR_PAD_V, DEFAULT_WIDGET_BAR_PAD_V)
            .coerceIn(0, WidgetBarMetrics.MAX_PADDING_V_DP)
        widgetBarActionOrder = p.getString(KEY_WIDGET_BAR_ACTION_ORDER, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        disabledProviders = p.getStringSet(KEY_DISABLED_PROVIDERS, null)?.toSet() ?: emptySet()
        providerOrder = p.getString(KEY_PROVIDER_ORDER, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        // An unknown id is dropped rather than failing the load, so removing a tier later can't
        // leave a stored set that no longer parses.
        matchOptions = p.getStringSet(KEY_MATCH_TIERS, null)
            ?.mapNotNull { id -> AppMatcher.MatchTier.entries.firstOrNull { it.id == id } }
            ?.toSet()
            ?.let { AppMatcher.MatchOptions(it) }
            ?: AppMatcher.MatchOptions.ALL

        unitSystemOverride = p.getString(KEY_UNIT_SYSTEM, null)
            ?.let { name -> MeasurementSystem.entries.firstOrNull { it.name == name } }
        preferredCurrency = p.getString(KEY_PREFERRED_CURRENCY, null)
        preferredZone = p.getString(KEY_PREFERRED_ZONE, null)
        animationMode = p.getString(KEY_ANIMATION_MODE, null)
            ?.let { name -> AnimationMode.entries.firstOrNull { it.name == name } }
            ?: AnimationMode.FOLLOW_SYSTEM
        AnswerProviders.enabledIds =
            p.getStringSet(KEY_ANSWER_PROVIDERS, null) ?: AnswerProviders.all.map { it.id }.toSet()

        searchWindowAdaptive = p.getBoolean(KEY_WINDOW_ADAPTIVE, true)
        searchWindowColor = p.getInt(KEY_WINDOW_COLOR, DEFAULT_WIDGET_BAR_COLOR)
        searchWindowAlpha = p.getInt(KEY_WINDOW_ALPHA, DEFAULT_WINDOW_ALPHA).coerceIn(0, 100)
        searchWindowCorner = p.getInt(KEY_WINDOW_CORNER, DEFAULT_WINDOW_CORNER_DP)
            .coerceIn(0, SEARCH_WINDOW_MAX_CORNER_DP)
        overlayGridRows = p.getInt(KEY_GRID_ROWS, DEFAULT_GRID_ROWS)
            .coerceIn(MIN_GRID_ROWS, MAX_GRID_ROWS)
        overlayIconSize = p.getInt(KEY_ICON_SIZE, DEFAULT_ICON_SIZE_DP)
            .coerceIn(MIN_ICON_SIZE_DP, MAX_ICON_SIZE_DP)
        overlayLabelSize = p.getInt(KEY_LABEL_SIZE, DEFAULT_LABEL_SIZE_SP)
            .coerceIn(MIN_LABEL_SIZE_SP, MAX_LABEL_SIZE_SP)

        launchMethod = LaunchMethod.fromId(p.getString(KEY_LAUNCH_METHOD, null))
            ?: LaunchMethod.STANDARD

        usageSource = p.getString(KEY_USAGE_SOURCE, null)
            ?.let { name -> UsageSource.entries.firstOrNull { it.name == name } }
            ?: DEFAULT_USAGE_SOURCE
    }

    fun setWidgetIconSizeDp(context: Context, dp: Int) {
        widgetIconSizeDp = dp
        prefs(context).edit().putInt(KEY_WIDGET_ICON_SIZE, dp).apply()
    }

    fun setWidgetIconOffsetDp(context: Context, dp: Int) {
        widgetIconOffsetDp = dp
        prefs(context).edit().putInt(KEY_WIDGET_ICON_OFFSET, dp).apply()
    }

    fun setNewUpdatedWindowHours(context: Context, hours: Long) {
        newUpdatedWindowHours = hours
        prefs(context).edit().putLong(KEY_NEW_UPDATED_WINDOW_HOURS, hours).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/** How the overlay's animations relate to the system-wide accessibility setting. */
enum class AnimationMode { FOLLOW_SYSTEM, ALWAYS_ON, ALWAYS_OFF }
