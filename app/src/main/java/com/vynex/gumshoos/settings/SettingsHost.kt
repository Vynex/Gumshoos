package com.vynex.gumshoos.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    data object WebSuggestions : Screen

    /**
     * The permission list as a settings screen. Not the first-run wizard, which is not a
     * destination at all — it is shown ahead of the whole back stack.
     */
    data object Permissions : Screen
    data object WidgetBar : Screen
    data object WidgetIcon : Screen
    data object Finder : Screen
    data object SearchWindow : Screen
    data object CurrencyPicker : Screen
    data object ZonePicker : Screen
    data object Advanced : Screen
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
 * dependency through five answer modules and an HTTP client, and a handful of flat screens three
 * levels deep at most, with one argument between them, is not the case that justifies one.
 *
 * The transitions are [AnimatedContent], which is a stock API rather than anything hand-rolled — it
 * keeps the outgoing screen composed and measured for the length of the slide, and settles cleanly
 * when Back is tapped twice into an animation already running. What is hand-rolled here is the
 * navigation, and it stays that way until something wants a feature that genuinely has to be
 * rebuilt: progress-driven predictive back is the one to watch, since driving a transition off
 * [androidx.activity.compose.PredictiveBackHandler] and settling it back on cancel is exactly what
 * navigation3 exists to hand over. That would be the point to take the dependency — note that it
 * pulls lifecycle-runtime 2.10.0, which the version catalog pins at 2.9.2 alongside a
 * lifecycle-service that would be left behind.
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

    AnimatedContent(
        targetState = stack,
        // The one thing standing between the cross-fade and the window behind it.
        //
        // Two screens fading past each other are both part-transparent at the same instant, so
        // their composite stops covering and whatever is *under* the Compose content shows through
        // the full screen area. That is `Theme.Gumshoos`, which is `Theme.Material.Light` with no
        // night variant, so it is white on every device — a white flash across a dark app. Painting
        // the scheme's own background here means the colour under the fade matches the background
        // both screens are already drawing, so nothing dips at all and only their content crosses.
        //
        // This belongs here rather than at `setContent`: a transition should not depend on what a
        // caller happens to have painted behind it, and `colorScheme.background` is a dynamic
        // colour that the window background could not be made to agree with in XML anyway.
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        transitionSpec = {
            // Equal depth counts as forward: the Edit deep link replaces [Home, X] with
            // [Home, ManageApps] without growing the stack, and that reads as going somewhere.
            val forward = targetState.size >= initialState.size
            val towards = if (forward) SlideDirection.Start else SlideDirection.End

            // An eighth of the container, carried by the fade — M3's shared axis X rather than a
            // full-width push. Every animation spec is left at its default, which is a spring in
            // each case, so there is no hand-picked duration here for the rest of the app to
            // disagree with. Material3's own motion tokens would have been the better source and
            // are not reachable: `MaterialTheme.motionScheme` is **internal** in 1.4.0, alongside
            // the other Expressive pieces. Its getter is public in the *bytecode*, which is not
            // the same question — Kotlin visibility lives in the metadata, and only the compiler
            // answers it.
            //
            // Nothing composable can be called in here: `transitionSpec` is a plain lambda in
            // animation 1.11.4, not the `@Composable` one several write-ups still show.
            //
            // No SizeTransform is passed either. Every screen is fillMaxSize, so the default
            // measures the same size on both sides and costs nothing; `using`, which is how the
            // older docs turn it off, no longer exists in 1.11.4.
            slideIntoContainer(towards) { it / 8 } + fadeIn() togetherWith
                slideOutOfContainer(towards) { it / 8 } + fadeOut()
        },
        label = "settings",
    ) { state ->
        // Reads `state`, never the `stack` it closes over. With the outer value the *outgoing*
        // screen redraws as the incoming destination the moment the stack changes, so the slide
        // runs between two copies of the same screen and reads as a glitch rather than a move.
        when (val current = state.last()) {
            Screen.Home -> SettingsHome(onOpen = ::push)
            Screen.Search -> SearchScreen(onBack = ::pop, onOpen = ::push)
            Screen.Contacts -> ContactsScreen(onBack = ::pop, onOpen = ::push)
            is Screen.ManageApps ->
                ManageAppsScreen(onBack = ::pop, highlightKey = current.highlightKey)
            Screen.HiddenContacts -> HiddenContactsScreen(onBack = ::pop)
            Screen.Matching -> MatchingScreen(onBack = ::pop)
            Screen.Providers -> ProvidersScreen(onBack = ::pop)
            Screen.WebSuggestions -> WebSuggestionsScreen(onBack = ::pop)
            Screen.WidgetBar -> WidgetBarScreen(onBack = ::pop)
            Screen.WidgetIcon -> WidgetIconScreen(onBack = ::pop)
            Screen.Finder -> FinderScreen(onBack = ::pop)
            Screen.SearchWindow -> SearchWindowScreen(onBack = ::pop)
            Screen.Permissions -> PermissionsScreen(onBack = ::pop)
            Screen.Answers -> AnswersScreen(onBack = ::pop, onOpen = ::push)
            Screen.Launching -> LaunchingScreen(onBack = ::pop)
            Screen.Behaviour -> BehaviourScreen(onBack = ::pop)
            Screen.Appearance -> AppearanceScreen(onBack = ::pop, onOpen = ::push)
            Screen.CurrencyPicker -> CurrencyPickerScreen(onBack = ::pop)
            Screen.ZonePicker -> ZonePickerScreen(onBack = ::pop)
            Screen.Advanced -> AdvancedScreen(onBack = ::pop)
        }
    }
}
