package com.vynex.gumshoos

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vynex.gumshoos.answers.AnswerProviders
import com.vynex.gumshoos.answers.MeasurementSystem

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

    /** How recently an app must have been installed or updated to show under NEW/UPDATED. */
    var newUpdatedWindowHours by mutableStateOf(DEFAULT_NEW_UPDATED_WINDOW_HOURS)
        private set

    /** Drawn diameter of the 1x1 widget's icon, in dp. */
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
