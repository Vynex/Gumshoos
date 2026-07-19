package com.vynex.gumshoos.widget

import com.vynex.gumshoos.SearchProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the action list promises, and the ordering it reuses from the provider row. */
class WidgetActionsTest {

    // --- the shipped set --------------------------------------------------------------------------

    @Test
    fun `action ids are unique`() {
        val ids = WidgetAction.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `request codes are unique and clear of the widget providers`() {
        // The load-bearing one. PendingIntent equality ignores extras, so a duplicated code lets one
        // action's intent overwrite another's — and 100 and 101 belong to the two widget providers.
        val codes = WidgetAction.entries.map { it.requestCode }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue("collides with a provider", codes.none { it == 100 || it == 101 })
    }

    @Test
    fun `request codes are not derived from ordinal`() {
        // An ordinal-derived code would silently reshuffle live PendingIntents the day this enum is
        // reordered. Asserting they don't start at zero is a cheap proxy for "written out".
        assertTrue(WidgetAction.entries.none { it.requestCode == it.ordinal })
    }

    @Test
    fun `the enum never outgrows the layout`() {
        // RemoteViews cannot add views, so the slots are fixed in XML. A fourth action without a
        // fourth slot would be dropped at render time with nothing to say so.
        assertTrue(
            "add a slot to widget_search_bar.xml before adding an action",
            WidgetAction.entries.size <= WidgetAction.SLOT_COUNT,
        )
    }

    @Test
    fun `brand marks are never tinted`() {
        // Tinting a multicolour mark flattens it to one tone. Both shipped icons are Google brand
        // artwork — the voice one is the four-colour mic, not a plain microphone glyph.
        WidgetAction.entries.forEach { action ->
            assertTrue("${action.id} would be flattened by a tint", !action.tintable)
        }
    }

    @Test
    fun `the shipped defaults fit in the slots and name real actions`() {
        assertTrue(WidgetAction.DEFAULT_ENABLED.size <= WidgetAction.SLOT_COUNT)
        WidgetAction.DEFAULT_ENABLED.forEach { id ->
            assertTrue("unknown default $id", WidgetAction.fromId(id) != null)
        }
    }

    @Test
    fun `an unknown id resolves to nothing rather than a default`() {
        assertNull(WidgetAction.fromId("nope"))
        assertNull(WidgetAction.fromId(null))
    }

    // --- slots ------------------------------------------------------------------------------------

    @Test
    fun `slots always fill every position`() {
        // decorate walks slots and actions together, so a short list has to arrive padded rather
        // than leaving stale icons visible in the leftover slots.
        assertEquals(WidgetAction.SLOT_COUNT, WidgetAction.slots(emptyList()).size)
        assertEquals(WidgetAction.SLOT_COUNT, WidgetAction.slots(listOf(WidgetAction.VOICE)).size)
    }

    @Test
    fun `slots keep the order they are given`() {
        val slots = WidgetAction.slots(listOf(WidgetAction.VOICE, WidgetAction.ASSISTANT))
        assertEquals(WidgetAction.VOICE, slots[0])
        assertEquals(WidgetAction.ASSISTANT, slots[1])
        assertNull(slots[2])
    }

    // --- ordering, reused from SearchProviders ---------------------------------------------------

    private fun ordered(order: List<String>) =
        SearchProviders.inConfiguredOrder(WidgetAction.entries.toList(), order, WidgetAction::id)
            .map { it.id }

    @Test
    fun `no configured order means declaration order`() {
        assertEquals(WidgetAction.entries.map { it.id }, ordered(emptyList()))
    }

    @Test
    fun `a configured order is applied`() {
        assertEquals(listOf("voice", "assistant"), ordered(listOf("voice", "assistant")))
    }

    @Test
    fun `an action missing from the order sorts to the end`() {
        // Same rule as the search providers: an action added in a later version lands at the end of
        // an order saved before it existed, rather than being dropped for not being listed.
        assertEquals(listOf("voice", "assistant"), ordered(listOf("voice")))
    }

    @Test
    fun `an order naming a retired action is ignored`() {
        // "lens" was shipped and then removed. A saved order still names it, and must not disturb
        // the actions that remain.
        assertEquals(listOf("voice", "assistant"), ordered(listOf("lens", "voice", "assistant")))
    }

    @Test
    fun `a retired id resolves to nothing`() {
        assertNull(WidgetAction.fromId("lens"))
    }

    @Test
    fun `reordering an action list preserves every entry`() {
        val ids = WidgetAction.entries.map { it.id }
        val moved = SearchProviders.reorder(ids, "voice", -1)
        assertEquals(ids.toSet(), moved.toSet())
        assertEquals(ids.size, moved.size)
    }
}
