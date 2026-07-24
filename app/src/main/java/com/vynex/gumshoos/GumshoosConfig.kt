package com.vynex.gumshoos

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vynex.gumshoos.answers.AnswerProviders
import com.vynex.gumshoos.answers.MeasurementSystem
import com.vynex.gumshoos.widget.WeatherUnit
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
    private const val KEY_INCOGNITO_KEYBOARD = "incognito_keyboard"
    private const val KEY_WEB_SUGGESTIONS = "web_suggestions_enabled"
    private const val KEY_WEB_SUGGESTION_SOURCE = "web_suggestion_source"
    private const val KEY_USAGE_SOURCE = "usage_source"
    private const val KEY_UNIT_SYSTEM = "unit_system"
    private const val KEY_PREFERRED_CURRENCY = "preferred_currency"
    private const val KEY_PREFERRED_ZONE = "preferred_zone"
    private const val KEY_ANIMATION_MODE = "animation_mode"
    private const val KEY_ANSWER_PROVIDERS = "answer_providers_enabled"
    private const val KEY_HIDDEN_APPS = "hidden_apps"
    private const val KEY_HIDDEN_CONTACTS = "hidden_contacts"
    private const val KEY_APP_ALIASES = "app_aliases_"
    /** Legacy: the *enabled* tier ids. Read only to migrate; superseded by [KEY_MATCH_TIERS_DISABLED]. */
    private const val KEY_MATCH_TIERS = "match_tiers"

    /**
     * The *disabled* tier ids. Storing the off set — not the on set — is what makes a tier added in a
     * later version default on for someone with settings already saved, the same reason the provider
     * lists do it. See [loadMatchOptions].
     */
    private const val KEY_MATCH_TIERS_DISABLED = "match_tiers_disabled"

    /**
     * The four tiers that existed while [KEY_MATCH_TIERS] stored the enabled set. Migration subtracts
     * the saved enabled set from *these* — never the current full set — so a tier introduced after
     * the enabled-set era (TYPO, and anything later) is never mistaken for one the user turned off.
     */
    private val LEGACY_MATCH_TIERS = setOf(
        AppMatcher.MatchTier.INITIALS,
        AppMatcher.MatchTier.CAPITALS,
        AppMatcher.MatchTier.SUBSTRING,
        AppMatcher.MatchTier.FUZZY,
    )
    private const val KEY_DISABLED_PROVIDERS = "disabled_providers"
    private const val KEY_PROVIDER_ORDER = "provider_order"
    private const val KEY_BROWSER_PROVIDERS = "browser_providers"
    private const val KEY_PREFERRED_BROWSER = "preferred_browser"
    private const val KEY_SETUP_COMPLETE = "setup_complete"
    private const val KEY_WIDGET_BAR_FILL_MODE = "widget_bar_fill_mode"
    private const val KEY_WIDGET_BAR_FILL_COLOR = "widget_bar_fill_color"
    private const val KEY_WIDGET_BAR_FILL_ALPHA = "widget_bar_fill_alpha"
    private const val KEY_WIDGET_BAR_WIREFRAME = "widget_bar_wireframe"
    private const val KEY_WIDGET_BAR_CORNER = "widget_bar_corner"
    private const val KEY_WIDGET_BAR_SHADOW = "widget_bar_shadow"
    private const val KEY_WIDGET_BAR_MONOCHROME = "widget_bar_monochrome"
    private const val KEY_WEATHER_API_KEY = "weather_api_key"
    private const val KEY_WEATHER_UNIT = "weather_unit"
    private const val KEY_WEATHER_PLACE = "weather_place"
    private const val KEY_WEATHER_PLACES = "weather_places"
    private const val KEY_WEATHER_LAT = "weather_lat"
    private const val KEY_WEATHER_LON = "weather_lon"
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
    private const val KEY_GRID_COLUMNS = "overlay_grid_columns"
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

    /**
     * Gumshoos' own launches by default. System usage knows about apps opened before Gumshoos
     * existed, which is the better ordering — but only once usage access is granted, and the
     * default has to be the option that works with no permission at all. A default naming a grant
     * nobody has made is a setting that reads as configured and orders by nothing.
     */
    val DEFAULT_USAGE_SOURCE = UsageSource.GUMSHOOS

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
     * The icon range was bounded by the column count: at a card of roughly 360dp of usable width
     * five columns give a cell of about 69dp, so anything past the mid-fifties starts crowding its
     * neighbours. Six columns narrow that cell to about 57dp, which the top of the icon range no
     * longer clears — the two settings can be driven into each other and the preview is what shows
     * it. Rows are the axis that costs height; columns cost none, so the card's geometry and
     * [overlaySettledHeight] are indifferent to them.
     */
    const val DEFAULT_GRID_ROWS = 2
    const val MIN_GRID_ROWS = 1
    const val MAX_GRID_ROWS = 3

    const val DEFAULT_GRID_COLUMNS = 5
    const val MIN_GRID_COLUMNS = 4
    const val MAX_GRID_COLUMNS = 6

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
     * Asks the keyboard not to learn from what is typed into the search field.
     *
     * Off by default: it costs the keyboard's personalisation on the one field where typing a few
     * letters and getting the right app is the entire product, so it is a trade rather than a
     * straight improvement. Whether the keyboard honours it is the keyboard's business — see
     * [OverlayContent]'s interceptor.
     */
    var incognitoKeyboard by mutableStateOf(false)
        private set

    /**
     * Live query completions from a search engine.
     *
     * **Off by default, and not a default to revisit**: switching it on sends what is typed here to
     * Google or DuckDuckGo, which is a decision only the person typing can make.
     */
    var webSuggestionsEnabled by mutableStateOf(false)
        private set

    /** Which engine completes a query. Meaningless while [webSuggestionsEnabled] is off. */
    var webSuggestionSource by mutableStateOf(SuggestionSource.DEFAULT)
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
    fun animationsEnabled(context: Context): Boolean =
        animationsEnabled(systemAnimationScale(context))

    /**
     * As above, against an already-read system scale.
     *
     * [animationMode] is a snapshot value held in memory and free to read, while the scale is a
     * ContentResolver query. Splitting them lets the overlay read the mode live — so a change to
     * this app's own setting applies to the very next open — while keeping the query off the show
     * path.
     */
    fun animationsEnabled(systemScale: Float): Boolean = when (animationMode) {
        AnimationMode.ALWAYS_OFF -> false
        AnimationMode.FOLLOW_SYSTEM -> systemScale != 0f
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

    /** [id] is a component key, or a package name from a build that stored them that way. */
    fun setAppAliases(context: Context, id: String, text: String) {
        val trimmed = text.trim()
        appAliases = if (trimmed.isEmpty()) {
            appAliases - id
        } else {
            appAliases + (id to trimmed)
        }
        putOrRemove(context, KEY_APP_ALIASES + id, trimmed.ifEmpty { null })
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
        // Store the disabled set (see KEY_MATCH_TIERS_DISABLED); this also retires the legacy key.
        val disabled = AppMatcher.MatchTier.entries.toSet() - tiers
        prefs(context).edit()
            .putStringSet(KEY_MATCH_TIERS_DISABLED, disabled.map { it.id }.toSet())
            .remove(KEY_MATCH_TIERS)
            .apply()
    }

    /**
     * Match options are stored as the disabled set, so a tier added later is on until turned off.
     * An unknown id is dropped rather than failing the load, so removing a tier stays safe.
     *
     * The first read after an update may still find the legacy enabled-set key: subtract that from
     * the tiers of its era ([LEGACY_MATCH_TIERS]) to recover the disabled set, which leaves any newer
     * tier on. No rewrite here — the next toggle persists the new format and drops the old key.
     */
    private fun loadMatchOptions(p: SharedPreferences): AppMatcher.MatchOptions {
        fun Set<String>.toTiers() =
            mapNotNull { id -> AppMatcher.MatchTier.entries.firstOrNull { it.id == id } }.toSet()

        val all = AppMatcher.MatchTier.entries.toSet()
        p.getStringSet(KEY_MATCH_TIERS_DISABLED, null)?.let {
            return AppMatcher.MatchOptions(all - it.toTiers())
        }
        p.getStringSet(KEY_MATCH_TIERS, null)?.let {
            return AppMatcher.MatchOptions(all - (LEGACY_MATCH_TIERS - it.toTiers()))
        }
        return AppMatcher.MatchOptions.ALL
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
     * Draw every icon on the bar in the contrast colour, brand marks included.
     *
     * Off by default: the per-icon [WidgetBarLogo.tintable] rule exists because flattening a brand
     * mark is normally wrong, and this is the deliberate exception rather than the starting point.
     */
    var widgetBarMonochrome by mutableStateOf(false)
        private set

    /**
     * OpenWeather key, blank until the user pastes one in.
     *
     * Plain SharedPreferences, not encrypted. That is a real statement rather than an oversight: on
     * a sideloaded personal app the prefs file is already only readable by this app and by root, so
     * EncryptedSharedPreferences would add a dependency and a keystore round trip to protect a
     * free-tier weather key from an attacker who by then has everything else too.
     */
    var weatherApiKey by mutableStateOf("")
        private set

    /** Display only. The request always asks for metric; conversion happens at draw time. */
    var weatherUnit by mutableStateOf(WeatherUnit.CELSIUS)
        private set

    /**
     * Typed place name, or blank for "wherever the phone is".
     *
     * Blank is the *default* and a real choice rather than an unset value, exactly as
     * [preferredBrowser]'s null is: it means follow the device, which is what someone who travels
     * wants, and it needs no geocoding call at all.
     */
    var weatherPlace by mutableStateOf("")
        private set

    /**
     * Places used before, offered in the dropdown under "current location".
     *
     * A comma-joined string rather than a StringSet, for the reason every ordered list here is:
     * SharedPreferences sets have no order, and most-recent-first is the whole value of this one.
     */
    var weatherPlaces by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * The picked place's coordinates, resolved once when it was chosen.
     *
     * Stored beside the label rather than looked up each refresh: a place does not move, so
     * geocoding on every fetch would double the request count for an unchanging answer — and would
     * make the weather stop working the moment the geocoder had a bad day.
     */
    var weatherLat by mutableStateOf(Double.NaN)
        private set

    var weatherLon by mutableStateOf(Double.NaN)
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
            monochrome = widgetBarMonochrome,
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

    fun setWeatherApiKey(context: Context, key: String) {
        weatherApiKey = key.trim()
        prefs(context).edit().putString(KEY_WEATHER_API_KEY, weatherApiKey).apply()
    }

    fun setWeatherUnit(context: Context, unit: WeatherUnit) {
        weatherUnit = unit
        prefs(context).edit().putString(KEY_WEATHER_UNIT, unit.id).apply()
    }

    /** Clears the place entirely, which is what "current location" means. */
    fun clearWeatherPlace(context: Context) {
        weatherPlace = ""
        weatherLat = Double.NaN
        weatherLon = Double.NaN
        prefs(context).edit()
            .putString(KEY_WEATHER_PLACE, "")
            .remove(KEY_WEATHER_LAT)
            .remove(KEY_WEATHER_LON)
            .apply()
    }

    fun setWeatherPlace(context: Context, place: String, lat: Double, lon: Double) {
        weatherLat = lat
        weatherLon = lon
        prefs(context).edit()
            .putFloat(KEY_WEATHER_LAT, lat.toFloat())
            .putFloat(KEY_WEATHER_LON, lon.toFloat())
            .apply()
        setWeatherPlace(context, place)
    }

    private fun setWeatherPlace(context: Context, place: String) {
        weatherPlace = place.trim()
        // Remembered so it can be picked again without retyping. Most recent first, and blank —
        // "current location" — is never in the list: it is the dropdown's fixed first row.
        if (weatherPlace.isNotBlank()) {
            weatherPlaces = (listOf(weatherPlace) + weatherPlaces.filter { it != weatherPlace })
                .take(MAX_WEATHER_PLACES)
        }
        prefs(context).edit()
            .putString(KEY_WEATHER_PLACE, weatherPlace)
            .putString(KEY_WEATHER_PLACES, weatherPlaces.joinToString(","))
            .apply()
    }

    /** Enough to pick from, few enough that the dropdown never needs scrolling. */
    private const val MAX_WEATHER_PLACES = 5

    fun setWidgetBarMonochrome(context: Context, enabled: Boolean) {
        widgetBarMonochrome = enabled
        prefs(context).edit().putBoolean(KEY_WIDGET_BAR_MONOCHROME, enabled).apply()
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
            remove(KEY_WIDGET_BAR_MONOCHROME)
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

    /**
     * Search providers set to open in a browser rather than in their own app, by id.
     *
     * Only the ones whose web page is the *same* search can be in here — see
     * [SearchProvider.canUseBrowser]. Storing the browser set rather than the app set keeps the app
     * the default, which is what a provider is for; it also means a provider that later gains the
     * choice starts where every other provider already is.
     */
    var browserProviders by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Whether [id] hands its query to a browser instead of opening its own app. */
    fun opensInBrowser(id: String): Boolean = id in browserProviders

    fun setOpensInBrowser(context: Context, id: String, browser: Boolean) {
        browserProviders = if (browser) browserProviders + id else browserProviders - id
        prefs(context).edit().putStringSet(KEY_BROWSER_PROVIDERS, browserProviders).apply()
    }

    /**
     * The browser a web search is forced into, or null to let the phone decide.
     *
     * Null is a real choice rather than an unset value: with several browsers installed and none set
     * as the system default, Android hands back its chooser, which is sometimes what someone wants.
     * Stored as a package name, so a browser that is later uninstalled simply stops resolving and
     * [SearchProviders.search] falls through to an unforced intent.
     */
    var preferredBrowser by mutableStateOf<String?>(null)
        private set

    fun setPreferredBrowser(context: Context, packageName: String?) {
        preferredBrowser = packageName
        prefs(context).edit().apply {
            if (packageName == null) remove(KEY_PREFERRED_BROWSER)
            else putString(KEY_PREFERRED_BROWSER, packageName)
        }.apply()
    }

    /** [id] is a component key, or a package name from a build that stored them that way. */
    fun setAppHidden(context: Context, id: String, hidden: Boolean) {
        hiddenApps = if (hidden) hiddenApps + id else hiddenApps - id
        prefs(context).edit().putStringSet(KEY_HIDDEN_APPS, hiddenApps).apply()
    }

    /**
     * Drops every hidden-app and custom-name setting belonging to an uninstalled package.
     *
     * Both stores are keyed by component now, so this matches a bare package key (older builds) or
     * any `pkg/class` component key under it. The trailing slash bounds the prefix — `com.foo/` can
     * never match `com.foobar/…`. Returns whether anything was removed, for the log.
     *
     * Must be called only on a genuine uninstall, never on the temporary unavailability (storage
     * unmounted, work profile off) that shares `AppRepository.removePackage`: those apps are coming
     * back and would expect their settings intact. The counts on the Apps screen read these maps'
     * sizes directly, so an orphan left here inflates a count no row can account for.
     */
    fun forgetPackage(context: Context, packageName: String): Boolean {
        val prefix = "$packageName/"
        fun belongs(id: String) = id == packageName || id.startsWith(prefix)

        val hidden = hiddenApps.filter(::belongs)
        val aliased = appAliases.keys.filter(::belongs)
        if (hidden.isEmpty() && aliased.isEmpty()) return false

        hiddenApps = hiddenApps - hidden.toSet()
        appAliases = appAliases - aliased.toSet()
        prefs(context).edit().apply {
            putStringSet(KEY_HIDDEN_APPS, hiddenApps)
            aliased.forEach { remove(KEY_APP_ALIASES + it) }
        }.apply()
        return true
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

    /**
     * A placeholder only. [load] replaces it before anything can read it — the real default needs a
     * Context to resolve, and load runs in `GumshoosApp.onCreate` ahead of every screen.
     */
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

    /**
     * What the window is coloured before anyone has picked anything.
     *
     * The deepest tone of the wallpaper's primary accent — the last swatch of the palette's first
     * row — so switching Adaptive off lands on a colour the picker visibly offers, with its tick
     * already on it, rather than on one that appears nowhere in the grid.
     *
     * It used to borrow the widget bar's white, which is the worst available answer here: the
     * overlay is hard-coded to the dark theme, so a white card is drawn under content that assumes
     * a dark surface, and switching Adaptive off jumped from a near-black surface straight to it.
     *
     * Resolved per launch rather than written to prefs, so it follows the wallpaper for as long as
     * nobody has overridden it — a stored copy would go stale the first time the wallpaper changed.
     * [KEY_WINDOW_COLOR] staying absent is what marks "never picked", which is also what makes
     * [resetSearchWindow] able to get back here.
     */
    private fun defaultSearchWindowColor(context: Context): Int =
        context.getColor(android.R.color.system_accent1_900)

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

    var overlayGridColumns by mutableStateOf(DEFAULT_GRID_COLUMNS)
        private set

    var overlayIconSize by mutableStateOf(DEFAULT_ICON_SIZE_DP)
        private set

    var overlayLabelSize by mutableStateOf(DEFAULT_LABEL_SIZE_SP)
        private set

    fun setOverlayGridRows(context: Context, rows: Int) {
        overlayGridRows = rows.coerceIn(MIN_GRID_ROWS, MAX_GRID_ROWS)
        prefs(context).edit().putInt(KEY_GRID_ROWS, overlayGridRows).apply()
    }

    fun setOverlayGridColumns(context: Context, columns: Int) {
        overlayGridColumns = columns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
        prefs(context).edit().putInt(KEY_GRID_COLUMNS, overlayGridColumns).apply()
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
            remove(KEY_GRID_COLUMNS)
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

    fun setIncognitoKeyboard(context: Context, enabled: Boolean) {
        incognitoKeyboard = enabled
        prefs(context).edit().putBoolean(KEY_INCOGNITO_KEYBOARD, enabled).apply()
    }

    fun setWebSuggestionsEnabled(context: Context, enabled: Boolean) {
        webSuggestionsEnabled = enabled
        prefs(context).edit().putBoolean(KEY_WEB_SUGGESTIONS, enabled).apply()
        // Nothing should survive the switch going off, including a section already on screen.
        if (!enabled) WebSuggestions.clear()
    }

    fun setWebSuggestionSource(context: Context, source: SuggestionSource) {
        webSuggestionSource = source
        prefs(context).edit().putString(KEY_WEB_SUGGESTION_SOURCE, source.id).apply()
        // The results on screen came from the other engine; keeping them would credit this one.
        WebSuggestions.clear()
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
        incognitoKeyboard = p.getBoolean(KEY_INCOGNITO_KEYBOARD, false)
        webSuggestionsEnabled = p.getBoolean(KEY_WEB_SUGGESTIONS, false)
        webSuggestionSource = SuggestionSource.fromId(p.getString(KEY_WEB_SUGGESTION_SOURCE, null))
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
        widgetBarMonochrome = p.getBoolean(KEY_WIDGET_BAR_MONOCHROME, false)
        weatherApiKey = p.getString(KEY_WEATHER_API_KEY, "").orEmpty()
        weatherUnit = WeatherUnit.fromId(p.getString(KEY_WEATHER_UNIT, null))
        weatherPlace = p.getString(KEY_WEATHER_PLACE, "").orEmpty()
        weatherPlaces = p.getString(KEY_WEATHER_PLACES, "").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        weatherLat = p.getFloat(KEY_WEATHER_LAT, Float.NaN).toDouble()
        weatherLon = p.getFloat(KEY_WEATHER_LON, Float.NaN).toDouble()
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
        browserProviders = p.getStringSet(KEY_BROWSER_PROVIDERS, null)?.toSet() ?: emptySet()
        preferredBrowser = p.getString(KEY_PREFERRED_BROWSER, null)

        matchOptions = loadMatchOptions(p)

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
        searchWindowColor = p.getInt(KEY_WINDOW_COLOR, defaultSearchWindowColor(context))
        searchWindowAlpha = p.getInt(KEY_WINDOW_ALPHA, DEFAULT_WINDOW_ALPHA).coerceIn(0, 100)
        searchWindowCorner = p.getInt(KEY_WINDOW_CORNER, DEFAULT_WINDOW_CORNER_DP)
            .coerceIn(0, SEARCH_WINDOW_MAX_CORNER_DP)
        overlayGridRows = p.getInt(KEY_GRID_ROWS, DEFAULT_GRID_ROWS)
            .coerceIn(MIN_GRID_ROWS, MAX_GRID_ROWS)
        overlayGridColumns = p.getInt(KEY_GRID_COLUMNS, DEFAULT_GRID_COLUMNS)
            .coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
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
/**
 * There is no "always on". It could never mean what it said: Compose's animation clock honours the
 * system scale and nothing in the app sits above it, so the option promised something it could not
 * deliver on exactly the phones where someone would reach for it. A stored `ALWAYS_ON` from an
 * older build falls through the name lookup in [GumshoosConfig.load] to [FOLLOW_SYSTEM], which is
 * what it effectively already was.
 */
enum class AnimationMode { FOLLOW_SYSTEM, ALWAYS_OFF }
