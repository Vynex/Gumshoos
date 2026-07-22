package com.vynex.gumshoos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ordering rules, which are pure list logic.
 *
 * Built from local providers rather than [SearchProviders.all] so these stay about the rules: what
 * the shipped list contains is a product decision that shouldn't break a test about sorting.
 */
class SearchProvidersTest {

    private fun provider(id: String) = SearchProvider(
        id = id,
        label = id,
        icon = 0,
        appUri = { "" },
        webUri = { "" },
    )

    private val a = provider("a")
    private val b = provider("b")
    private val c = provider("c")
    private val declared = listOf(a, b, c)

    private fun order(order: List<String>, providers: List<SearchProvider> = declared) =
        SearchProviders.inConfiguredOrder(providers, order).map { it.id }

    // --- ordering ------------------------------------------------------------------------------

    @Test
    fun `no configured order means declaration order`() {
        assertEquals(listOf("a", "b", "c"), order(emptyList()))
    }

    @Test
    fun `a configured order is applied`() {
        assertEquals(listOf("c", "a", "b"), order(listOf("c", "a", "b")))
    }

    @Test
    fun `a provider missing from the order sorts to the end`() {
        // This is what lets a provider added in a later version show up at all: the saved order
        // predates it, and dropping unlisted ids would hide it from everyone with saved settings.
        assertEquals(listOf("c", "a", "b"), order(listOf("c", "a")))
    }

    @Test
    fun `several unlisted providers keep their declaration order`() {
        assertEquals(listOf("c", "a", "b"), order(listOf("c")))
    }

    @Test
    fun `an order naming a provider that no longer exists is harmless`() {
        assertEquals(listOf("b", "a"), order(listOf("gone", "b", "a"), listOf(a, b)))
    }

    // --- reordering ----------------------------------------------------------------------------

    private val ids = listOf("a", "b", "c")

    /** Every row on screen, which is the Providers screen and the simple case. */
    private fun move(from: Int, to: Int, list: List<String> = ids) =
        SearchProviders.reorderVisible(list, list, from, to)

    @Test
    fun `moving up swaps with the previous entry`() {
        assertEquals(listOf("b", "a", "c"), move(1, 0))
    }

    @Test
    fun `moving down swaps with the next entry`() {
        assertEquals(listOf("a", "c", "b"), move(1, 2))
    }

    @Test
    fun `a drag across several places lands where it was dropped`() {
        assertEquals(listOf("b", "c", "a"), move(0, 2))
        assertEquals(listOf("c", "a", "b"), move(2, 0))
    }

    @Test
    fun `moving past either end is a no-op`() {
        // What the "move up" action on the top row does, so it needs no guard at the call site.
        assertEquals(ids, move(0, -1))
        assertEquals(ids, move(2, 3))
    }

    @Test
    fun `moving onto itself is a no-op`() {
        assertEquals(ids, move(1, 1))
    }

    @Test
    fun `reordering preserves every id`() {
        assertEquals(ids.toSet(), move(2, 0).toSet())
        assertEquals(ids.size, move(2, 0).size)
    }

    // --- reordering a visible subset --------------------------------------------------------

    // The widget bar's case: uninstalled actions are saved in the order but aren't on screen, and
    // must not be shuffled by a drag between the rows that are.

    private val saved = listOf("a", "gone", "b", "c")
    private val shown = listOf("a", "b", "c")

    @Test
    fun `an id that isn't on screen keeps its own slot`() {
        // "b" and "a" trade places around it; "gone" stays at index 1 either way.
        assertEquals(listOf("b", "gone", "a", "c"), SearchProviders.reorderVisible(saved, shown, 1, 0))
    }

    @Test
    fun `a drag past an off-screen id still moves one visible place`() {
        assertEquals(listOf("b", "gone", "a", "c"), SearchProviders.reorderVisible(saved, shown, 0, 1))
    }

    @Test
    fun `a drag to the end of the visible rows keeps the off-screen id in place`() {
        assertEquals(listOf("b", "gone", "c", "a"), SearchProviders.reorderVisible(saved, shown, 0, 2))
    }

    @Test
    fun `indices are read against the visible rows, not the saved order`() {
        // Index 2 is "c" on screen but "b" in the saved list. Reading it against the saved list
        // would move the wrong row — the bug this function exists to make impossible.
        assertEquals(listOf("c", "gone", "a", "b"), SearchProviders.reorderVisible(saved, shown, 2, 0))
    }

    @Test
    fun `a visible id missing from the saved order is a no-op rather than a crash`() {
        assertEquals(saved, SearchProviders.reorderVisible(saved, listOf("a", "b", "zzz"), 0, 1))
    }

    // --- what the shipped set promises ----------------------------------------------------------

    @Test
    fun `provider ids are unique and stable keys`() {
        val all = SearchProviders.all.map { it.id }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `only the app-gated providers carry a required package`() {
        val gated = SearchProviders.all.filter { it.requiresPackage != null }.map { it.id }
        assertEquals(setOf("spotify", "chatgpt", "duckduckgo"), gated.toSet())
    }

    /**
     * Gating is what hides a provider and moves it to the settings screen's Uninstalled group, so
     * the two have to agree: a provider with a required package must have somewhere to send the user
     * to install it, and that is the same string.
     */
    @Test
    fun `a gated provider names a package the store can be opened on`() {
        SearchProviders.all.filter { it.requiresPackage != null }.forEach { provider ->
            val required = provider.requiresPackage.orEmpty()
            assert(required.count { it == '.' } >= 2) { "${provider.id}: '$required' isn't a package" }
        }
    }

    @Test
    fun `only a search engine offers the browser choice`() {
        val choosable = SearchProviders.all.filter { it.canUseBrowser }.map { it.id }
        assertEquals(setOf("google", "duckduckgo"), choosable.toSet())
    }

    // --- the gate, and the switch that opens it -------------------------------------------------

    @Test
    fun `a gated provider is unavailable while its app is missing`() {
        assertEquals(
            true,
            SearchProviders.needsMissingApp(
                SearchProviders.duckDuckGo,
                absent = setOf("duckduckgo"),
                browserMode = emptySet(),
            ),
        )
    }

    /**
     * The whole point of the switch. Without this, choosing "open in a browser" for DuckDuckGo would
     * leave the row hidden, and the setting would appear to do nothing.
     */
    @Test
    fun `browser mode makes a missing app irrelevant`() {
        assertEquals(
            false,
            SearchProviders.needsMissingApp(
                SearchProviders.duckDuckGo,
                absent = setOf("duckduckgo"),
                browserMode = setOf("duckduckgo"),
            ),
        )
    }

    @Test
    fun `an installed app is available in either mode`() {
        listOf(emptySet(), setOf("duckduckgo")).forEach { mode ->
            assertEquals(
                false,
                SearchProviders.needsMissingApp(
                    SearchProviders.duckDuckGo,
                    absent = emptySet(),
                    browserMode = mode,
                ),
            )
        }
    }

    /** Google needs no app, so the gate never applies to it whichever way the switch is set. */
    @Test
    fun `an ungated provider is never held back by a missing app`() {
        assertEquals(
            false,
            SearchProviders.needsMissingApp(
                SearchProviders.google,
                absent = setOf("duckduckgo", "spotify"),
                browserMode = emptySet(),
            ),
        )
    }
}
