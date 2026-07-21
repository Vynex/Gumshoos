package com.vynex.gumshoos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The app's theme: always the wallpaper-derived dynamic scheme.
 *
 * There is no hand-picked fallback palette. Dynamic colour arrives with API 31 and `minSdk` is 31,
 * so every device this runs on has one — the template's purple scheme could never have been
 * selected, and a scheme that cannot be reached is a scheme nobody checks.
 *
 * [darkTheme] is still a parameter because the search overlay overrides it: that window draws over
 * whatever is on screen and is dark unconditionally, rather than following the phone.
 */
@Composable
fun GumshoosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (darkTheme) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        },
        typography = Typography,
        content = content,
    )
}
