package com.vynex.gumshoos.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vynex.gumshoos.AppPermission
import com.vynex.gumshoos.GumshoosConfig

/** Every destination in settings. */
internal sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Contacts : Screen
    data object Answers : Screen
    data object Launching : Screen
    data object Behaviour : Screen
    data object Appearance : Screen

    /** [highlightKey] is a component key to scroll to and pulse, set only on the Edit deep link. */
    data class ManageApps(val highlightKey: String? = null) : Screen
    data object HiddenContacts : Screen
    data object Matching : Screen
    data object Providers : Screen

    /**
     * The permission list as a settings screen. Not the first-run wizard, which is not a
     * destination at all — it is shown ahead of the whole back stack.
     */
    data object Permissions : Screen
    data object WidgetBar : Screen
    data object WidgetIcon : Screen
    data object SearchWindow : Screen
    data object CurrencyPicker : Screen
    data object ZonePicker : Screen
    data object Advanced : Screen
    data object EntryPoints : Screen
}

/**
 * A request from the search window's Edit action to open Manage apps on one app.
 *
 * [nonce] distinguishes two requests for the *same* app, so tapping Edit on it twice re-fires the
 * scroll and pulse rather than being coalesced away by an unchanged key.
 */
data class ManageAppsRequest(val componentKey: String, val nonce: Long)

/**
 * Navigation for the settings app.
 *
 * A list used as a back stack, rather than navigation-compose. The project has added no Gradle
 * dependency through five answer modules and an HTTP client, and a handful of flat screens with no
 * arguments, no deep links and no transitions is not the case that justifies one.
 */
@Composable
fun SettingsHost(manageAppsRequest: ManageAppsRequest? = null) {
    val context = LocalContext.current

    // Decided once per launch, not continuously. Re-reading it live would yank the screen away
    // mid-grant — the user returns from the system's permission screen and setup vanishes before
    // they've seen the optional two.
    var showSetup by remember {
        mutableStateOf(
            AppPermission.shouldShowSetup(
                missingRequired = AppPermission.required.any { !it.isGranted(context) },
                setupComplete = GumshoosConfig.setupComplete,
            )
        )
    }
    if (showSetup) {
        SetupScreen(onDone = { showSetup = false })
        return
    }

    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }

    fun push(screen: Screen) { stack = stack + screen }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    // Keyed on the nonce so a repeat request for the same app still re-navigates. Resets to
    // Home → ManageApps rather than pushing, so Back always lands on Home from a deep link.
    LaunchedEffect(manageAppsRequest?.nonce) {
        val key = manageAppsRequest?.componentKey ?: return@LaunchedEffect
        stack = listOf(Screen.Home, Screen.ManageApps(key))
    }

    // Only intercepts Back while somewhere to go back to; at Home the activity handles it and exits.
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val current = stack.last()) {
        Screen.Home -> SettingsHome(onOpen = ::push)
        Screen.Search -> SearchScreen(onBack = ::pop, onOpen = ::push)
        Screen.Contacts -> ContactsScreen(onBack = ::pop, onOpen = ::push)
        is Screen.ManageApps -> ManageAppsScreen(onBack = ::pop, highlightKey = current.highlightKey)
        Screen.HiddenContacts -> HiddenContactsScreen(onBack = ::pop)
        Screen.Matching -> MatchingScreen(onBack = ::pop)
        Screen.Providers -> ProvidersScreen(onBack = ::pop)
        Screen.WidgetBar -> WidgetBarScreen(onBack = ::pop)
        Screen.WidgetIcon -> WidgetIconScreen(onBack = ::pop)
        Screen.SearchWindow -> SearchWindowScreen(onBack = ::pop)
        Screen.Permissions -> PermissionsScreen(onBack = ::pop)
        Screen.Answers -> AnswersScreen(onBack = ::pop, onOpen = ::push)
        Screen.Launching -> LaunchingScreen(onBack = ::pop)
        Screen.Behaviour -> BehaviourScreen(onBack = ::pop)
        Screen.Appearance -> AppearanceScreen(onBack = ::pop, onOpen = ::push)
        Screen.CurrencyPicker -> CurrencyPickerScreen(onBack = ::pop)
        Screen.ZonePicker -> ZonePickerScreen(onBack = ::pop)
        Screen.Advanced -> AdvancedScreen(onBack = ::pop, onOpen = ::push)
        Screen.EntryPoints -> EntryPointsScreen(onBack = ::pop)
    }
}
