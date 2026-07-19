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

    @Test
    fun `moving up swaps with the previous entry`() {
        assertEquals(listOf("b", "a", "c"), SearchProviders.reorder(ids, "b", -1))
    }

    @Test
    fun `moving down swaps with the next entry`() {
        assertEquals(listOf("a", "c", "b"), SearchProviders.reorder(ids, "b", 1))
    }

    @Test
    fun `moving past either end is a no-op`() {
        assertEquals(ids, SearchProviders.reorder(ids, "a", -1))
        assertEquals(ids, SearchProviders.reorder(ids, "c", 1))
    }

    @Test
    fun `moving an id that isn't there is a no-op`() {
        assertEquals(ids, SearchProviders.reorder(ids, "zzz", 1))
    }

    @Test
    fun `reordering preserves every id`() {
        assertEquals(ids.toSet(), SearchProviders.reorder(ids, "c", -1).toSet())
        assertEquals(ids.size, SearchProviders.reorder(ids, "c", -1).size)
    }

    // --- what the shipped set promises ----------------------------------------------------------

    @Test
    fun `provider ids are unique and stable keys`() {
        val all = SearchProviders.all.map { it.id }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `spotify and chatgpt are gated on their app being installed`() {
        val gated = SearchProviders.all.filter { it.requiresPackage != null }.map { it.id }
        assertEquals(setOf("spotify", "chatgpt"), gated.toSet())
    }
}
