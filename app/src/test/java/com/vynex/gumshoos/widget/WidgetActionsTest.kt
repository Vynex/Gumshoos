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
        // RemoteViews cannot add views, so the slots are fixed in XML. An action past the last slot
        // would be dropped at render time with nothing to say so. Equality is the interesting case:
        // every action can be placed at once, so the order is a choice rather than a queue.
        assertTrue(
            "add a slot to widget_search_bar.xml before adding an action",
            WidgetAction.entries.size <= WidgetAction.SLOT_COUNT,
        )
    }

    @Test
    fun `brand marks are never tinted`() {
        // Tinting a multicolour mark flattens it to one tone. All three shipped icons are Google
        // brand artwork — the voice one is the four-colour mic, not a plain microphone glyph.
        WidgetAction.entries.forEach { action ->
            assertTrue("${action.id} would be flattened by a tint", !action.tintable)
        }
    }

    @Test
    fun `a text action is an ordinary slot action`() {
        // Every slot holds all three views and shows one, so kind changes what a slot draws and
        // nothing about where it can be. If this stopped being true the text actions would quietly
        // lose the ability to be dragged, which is the whole point of the trio.
        assertEquals(SlotKind.CLOCK, WidgetAction.DATE.kind)
        assertEquals(SlotKind.TEXT, WidgetAction.WEATHER.kind)
        assertTrue(WidgetAction.entries.containsAll(listOf(WidgetAction.DATE, WidgetAction.WEATHER)))
    }

    @Test
    fun `only the date self-updates, and that is why it is a clock`() {
        // The distinction that matters: a CLOCK keeps itself current, a TEXT cannot and needs a
        // staleness policy behind it. Weather being marked CLOCK would leave it frozen on whatever
        // was last pushed with nothing to say so.
        assertEquals(
            listOf(WidgetAction.DATE),
            WidgetAction.entries.filter { it.kind == SlotKind.CLOCK },
        )
    }

    @Test
    fun `a text action is never given icon treatment`() {
        // Both would be applied to an ImageView this action never reaches, so a non-default here
        // would be a value with nowhere to land — and a reader would reasonably believe it did
        // something.
        assertTrue(!WidgetAction.DATE.tintable)
        assertEquals(1f, WidgetAction.DATE.iconScale, 0.0001f)
    }

    @Test
    fun `date can be ordered to any position, like any other action`() {
        // The point of pairing an ImageView with a TextClock in every slot. If the date could only
        // sit at one end this would be the test that failed.
        assertEquals("date", ordered(listOf("date")).first())
        assertEquals("date", ordered(listOf("voice", "date")) [1])
        assertEquals("date", ordered(listOf("voice", "assistant", "lens", "date"))[3])
    }

    @Test
    fun `date is off out of the box`() {
        // Widget actions store the enabled set precisely so a new one cannot shove itself into a
        // bar someone has already arranged.
        assertTrue(WidgetAction.DATE.id !in WidgetAction.DEFAULT_ENABLED)
    }

    @Test
    fun `an optical trim never grows a mark past its box`() {
        // Above 1 the artwork would be drawn larger than the bitmap it goes into and clipped at the
        // edge. Every mark inks its full canvas now, so there is no transparent margin left to
        // absorb the overflow — which there was, exactly once, before Lens was cropped.
        WidgetAction.entries.forEach {
            assertTrue("${it.id} scales past its box", it.iconScale in 0.5f..1f)
        }
    }

    @Test
    fun `only Lens is trimmed, and the rest are left alone`() {
        // Pins the intent rather than the number: a trim is a per-mark judgement about shape, so a
        // second one appearing should be a deliberate edit here and not a drifted default.
        assertEquals(1f, WidgetAction.ASSISTANT.iconScale, 0.0001f)
        assertEquals(1f, WidgetAction.VOICE.iconScale, 0.0001f)
        assertTrue(WidgetAction.LENS.iconScale < 1f)
    }

    @Test
    fun `only Lens is gated on an app being installed`() {
        // An action carrying a requiredPackage disappears from the bar wherever that app is absent.
        // That is right for Lens — every other entry point the Google app exposes was fired by hand
        // and drew nothing — and wrong for the other two, which end on an unscoped intent and land
        // somewhere on any phone. Gating one of those would silently empty the bar for everyone.
        assertEquals(
            listOf(WidgetAction.LENS),
            WidgetAction.entries.filter { it.requiredPackage != null },
        )
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

    private val slotIds = WidgetAction.entries.map { it.id }

    @Test
    fun `no configured order means declaration order`() {
        assertEquals(slotIds, ordered(emptyList()))
    }

    @Test
    fun `a configured order is applied`() {
        // Unlisted ids keep declaration order at the end, so date and weather trail in that order.
        assertEquals(
            listOf("lens", "voice", "assistant", "date", "weather"),
            ordered(listOf("lens", "voice", "assistant")),
        )
    }

    @Test
    fun `an action missing from the order sorts to the end`() {
        // Same rule as the search providers: an action added in a later version lands at the end of
        // an order saved before it existed, rather than being dropped for not being listed.
        assertEquals(
            listOf("voice", "assistant", "lens", "date", "weather"),
            ordered(listOf("voice", "assistant")),
        )
    }

    @Test
    fun `an order naming an action that no longer exists is ignored`() {
        // Ids come out of saved preferences, so the list can name something a later build dropped.
        // It must not disturb the actions that remain.
        assertEquals(slotIds, ordered(listOf("gone") + slotIds))
    }

    @Test
    fun `reordering an action list preserves every entry`() {
        val ids = slotIds
        val from = ids.indexOf("voice")
        val moved = SearchProviders.reorderVisible(ids, ids, from, from - 1)
        assertEquals(ids.toSet(), moved.toSet())
        assertEquals(ids.size, moved.size)
    }

    @Test
    fun `dragging among installed actions leaves an uninstalled one where it was`() {
        // The widget bar's real case: Lens drops out of the rows on screen when its app isn't
        // there, but stays in the saved order so installing it returns it to this position.
        val ids = listOf("google", "lens", "voice", "assistant")
        val shown = listOf("google", "voice", "assistant")
        assertEquals(
            listOf("voice", "lens", "google", "assistant"),
            SearchProviders.reorderVisible(ids, shown, 1, 0),
        )
    }
}
