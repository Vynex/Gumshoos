package com.vynex.gumshoos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup gate and what the permission list promises.
 *
 * [AppPermission.isGranted] is Android-dependent and verified on device; the rule for when setup
 * appears, and the copy every screen now shares, are not.
 */
class AppPermissionTest {

    // --- when setup appears ---------------------------------------------------------------------

    @Test
    fun `setup appears on a first run with everything already granted`() {
        // Otherwise the optional permissions are never explained — someone who granted the overlay
        // from the app info screen would meet contacts access as an unexplained refusal.
        assertTrue(AppPermission.shouldShowSetup(missingRequired = false, setupComplete = false))
    }

    @Test
    fun `setup reappears when a required permission is revoked`() {
        // Search has silently stopped working at this point; this screen is what says so.
        assertTrue(AppPermission.shouldShowSetup(missingRequired = true, setupComplete = true))
    }

    @Test
    fun `setup stays out of the way once done and granted`() {
        assertFalse(AppPermission.shouldShowSetup(missingRequired = false, setupComplete = true))
    }

    @Test
    fun `an unfinished setup still shows with the requirement missing`() {
        assertTrue(AppPermission.shouldShowSetup(missingRequired = true, setupComplete = false))
    }

    // --- the list itself --------------------------------------------------------------------------

    @Test
    fun `only the overlay permission is required`() {
        assertEquals(listOf(AppPermission.OVERLAY), AppPermission.required)
    }

    @Test
    fun `required and optional together cover everything, without overlap`() {
        assertEquals(
            AppPermission.entries.toSet(),
            (AppPermission.required + AppPermission.optional).toSet(),
        )
        assertEquals(
            AppPermission.entries.size,
            AppPermission.required.size + AppPermission.optional.size,
        )
    }

    @Test
    fun `every permission explains itself`() {
        // This copy is the whole point of the screen, and it's shared with the settings screens —
        // an empty reason would leave one of them with a blank note.
        AppPermission.entries.forEach { permission ->
            assertTrue(permission.title.isNotBlank())
            assertTrue("${permission.name} has no reason", permission.why.isNotBlank())
        }
    }

    @Test
    fun `every optional permission says what is lost without it`() {
        // The required one is exempt: what's lost is everything, which the screen says separately.
        AppPermission.optional.forEach { permission ->
            assertTrue("${permission.name} has no cost", permission.withoutIt.isNotBlank())
        }
        assertEquals("", AppPermission.OVERLAY.withoutIt)
    }

    @Test
    fun `notification permission is not among them`() {
        // Deliberate: leaving POST_NOTIFICATIONS denied is what keeps the foreground service out
        // of the shade. Asking for it would make the app worse, so it must never be listed.
        assertTrue(AppPermission.entries.none { it.title.contains("otification") })
    }
}
