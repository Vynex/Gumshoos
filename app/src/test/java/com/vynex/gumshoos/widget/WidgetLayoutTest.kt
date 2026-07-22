package com.vynex.gumshoos.widget

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the widget layouts against view classes RemoteViews refuses to inflate.
 *
 * RemoteViews inflates in the *launcher's* process, behind a filter that only admits classes
 * annotated `@RemoteViews.RemoteView`. Anything else fails the whole layout, and the launcher
 * reports it as "An error occurred when loading widget" — no logcat entry of ours, no clue which
 * view was at fault, and nothing the app can catch, because the failure happens in another process.
 *
 * `<View>` and `<Space>` both look like obvious spacers and neither is permitted. That shipped once;
 * this is here so it fails at build time instead.
 */
class WidgetLayoutTest {

    /**
     * Every class carrying the annotation, read off android.jar.
     *
     * Custom views are impossible here anyway — a RemoteViews layout can only use framework classes
     * — so an exhaustive list is the whole rule rather than an approximation of it.
     */
    private val permitted = setOf(
        "AbsoluteLayout", "AdapterViewFlipper", "AnalogClock", "Button", "CheckBox", "Chronometer",
        "FrameLayout", "GridLayout", "GridView", "ImageButton", "ImageView", "LinearLayout",
        "ListView", "ProgressBar", "RadioButton", "RadioGroup", "RelativeLayout", "StackView",
        "Switch", "TextClock", "TextView", "ViewFlipper", "ViewStub",
    )

    private val layouts = listOf("widget_search_bar.xml", "widget_search_icon.xml")

    private fun layoutDir(): File {
        // The working directory is the Gradle module, but don't rely on exactly which one — walk up
        // until the res tree appears, so this doesn't turn into a path-guessing exercise.
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/res/layout")
            if (candidate.isDirectory) return candidate
            val here = File(dir, "src/main/res/layout")
            if (here.isDirectory) return here
            dir = dir.parentFile
        }
        throw AssertionError("could not locate res/layout from ${File(".").absolutePath}")
    }

    @Test
    fun `widget layouts use only view classes RemoteViews can inflate`() {
        val tag = Regex("<\\s*([A-Za-z][A-Za-z0-9_.]*)")
        layouts.forEach { name ->
            val file = File(layoutDir(), name)
            assertTrue("missing layout $name", file.isFile)

            // Strip comments first: this file's own explanatory note names the forbidden classes.
            val xml = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

            tag.findAll(xml)
                .map { it.groupValues[1] }
                .filterNot { it == "xml" || it.startsWith("/") }
                .forEach { element ->
                    assertTrue(
                        "$name inflates <$element>, which RemoteViews will reject in the launcher",
                        element in permitted,
                    )
                }
        }
    }

    @Test
    fun `the bar layout has a slot for every action`() {
        // decorate addresses slots by id; a missing one would be an unresolved reference, but a
        // spare would silently never be filled.
        val xml = File(layoutDir(), "widget_search_bar.xml").readText()
        for (index in 1..WidgetAction.SLOT_COUNT) {
            assertTrue(
                "widget_search_bar.xml has no widget_action_$index",
                xml.contains("@+id/widget_action_$index"),
            )
        }
    }

    /**
     * Every dimen the bar's layout names must also be pushed as a RemoteViews action.
     *
     * A launcher reuses the hierarchy it already inflated when the layout id has not changed:
     * actions are re-applied to it, an XML-only attribute is not. So a dimen edited in one place
     * takes effect on a newly placed widget and silently not on the ones already on the home
     * screen — and the first thing anyone does to check is place a new one, which shows the *new*
     * value and hides the bug. `widget_bar_action_size` did precisely this going from 22dp to 21dp.
     *
     * Reads the source rather than the resource so it is checking what a human wrote. Crude on
     * purpose: it asks only whether the dimen's name appears somewhere in the provider, which is
     * enough to make someone adding a styling attribute stop and think about where it is applied.
     */
    @Test
    fun `every styled dimen in the bar layout is also pushed at runtime`() {
        val layout = File(layoutDir(), "widget_search_bar.xml").readText()
        val provider = providerSource().readText()

        // Structural values that cannot go stale usefully: they never change, and match_parent /
        // wrap_content are not dimens at all.
        val exempt = setOf("widget_bar_inset")

        val named = Regex("""@dimen/(\w+)""").findAll(layout)
            .map { it.groupValues[1] }
            .toSet() - exempt

        assertTrue("layout names no dimens — did the file move?", named.isNotEmpty())
        named.forEach { dimen ->
            assertTrue(
                "$dimen is set in widget_search_bar.xml but never pushed in SearchWidgetProvider, " +
                    "so editing it will not reach widgets already placed",
                provider.contains(dimen),
            )
        }
    }

    private fun providerSource(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/vynex/gumshoos/SearchWidgetProvider.kt")
            if (candidate.isFile) return candidate
            val here = File(dir, "src/main/java/com/vynex/gumshoos/SearchWidgetProvider.kt")
            if (here.isFile) return here
            dir = dir.parentFile
        }
        throw AssertionError("could not locate SearchWidgetProvider.kt")
    }
}
