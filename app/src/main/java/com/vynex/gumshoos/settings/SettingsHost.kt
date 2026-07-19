package com.vynex.gumshoos.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Every destination in settings. */
internal sealed interface Screen {
    data object Home : Screen
    data object Apps : Screen
    data object Contacts : Screen
    data object Answers : Screen
    data object Launching : Screen
    data object Behaviour : Screen
    data object Advanced : Screen
}

/**
 * Navigation for the settings app.
 *
 * A list used as a back stack, rather than navigation-compose. The project has added no Gradle
 * dependency through five answer modules and an HTTP client, and a handful of flat screens with no
 * arguments, no deep links and no transitions is not the case that justifies one.
 */
@Composable
fun SettingsHost() {
    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }

    fun push(screen: Screen) { stack = stack + screen }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    // Only intercepts Back while somewhere to go back to; at Home the activity handles it and exits.
    BackHandler(enabled = stack.size > 1) { pop() }

    when (stack.last()) {
        Screen.Home -> SettingsHome(onOpen = ::push)
        Screen.Apps -> AppsScreen(onBack = ::pop)
        Screen.Contacts -> ContactsScreen(onBack = ::pop)
        Screen.Answers -> AnswersScreen(onBack = ::pop)
        Screen.Launching -> LaunchingScreen(onBack = ::pop)
        Screen.Behaviour -> BehaviourScreen(onBack = ::pop)
        Screen.Advanced -> AdvancedScreen(onBack = ::pop)
    }
}
