package de.werklade.cratoniremote.companion

import de.werklade.cratoniremote.data.ConfirmationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order behind [CompanionSetup.cancelAssociation]: nothing is forgotten before the association really is
 * gone. Forgetting first would leave an association behind that no marker points to any more — and that is
 * the one state nothing ever takes back.
 */
class PairingCleanupTest {

    @Test
    fun `a removed association is forgotten`() {
        val decision = PairingCleanup.decide(
            associationId = 7,
            remainingIds = emptyList(),
            storedId = 7,
            pendingId = 7,
            serviceRunning = true,
        )

        assertEquals(PairingCleanup.Decision(clearPending = true, clearConfirmed = true, stopService = true), decision)
    }

    /** `disassociate` failed: the markers stay, so the next app start tries again. */
    @Test
    fun `an association that is still there keeps its marker and its stored id`() {
        val decision = PairingCleanup.decide(
            associationId = 7,
            remainingIds = listOf(7),
            storedId = 7,
            pendingId = 7,
            serviceRunning = true,
        )

        assertFalse(decision.clearPending)
        assertFalse(decision.clearConfirmed)
    }

    @Test
    fun `markers of other associations are left alone`() {
        val decision = PairingCleanup.decide(
            associationId = 9,
            remainingIds = listOf(7),
            storedId = 7,
            pendingId = 8,
            serviceRunning = false,
        )

        assertFalse(decision.clearPending)
        assertFalse(decision.clearConfirmed)
    }

    /** A confirmed remote that is still set up keeps its connection: the service has work to do. */
    @Test
    fun `the service keeps running while a confirmed association is left`() {
        val decision = PairingCleanup.decide(
            associationId = 9,
            remainingIds = listOf(8),
            storedId = 8,
            pendingId = 9,
            serviceRunning = true,
        )

        assertFalse(decision.stopService)
    }

    @Test
    fun `the service is stopped when no confirmed association is left`() {
        assertTrue(
            PairingCleanup.decide(
                associationId = 7,
                remainingIds = emptyList(),
                storedId = null,
                pendingId = 7,
                serviceRunning = true,
            ).stopService,
        )
        // The stored association is the one that was just removed.
        assertTrue(
            PairingCleanup.decide(
                associationId = 7,
                remainingIds = emptyList(),
                storedId = 7,
                pendingId = null,
                serviceRunning = true,
            ).stopService,
        )
        // A stored association that no longer exists is no reason to keep it running either.
        assertTrue(
            PairingCleanup.decide(
                associationId = 7,
                remainingIds = emptyList(),
                storedId = 8,
                pendingId = null,
                serviceRunning = true,
            ).stopService,
        )
    }

    // After a reconcile, which removes several associations at once and may keep one.

    /** The press arrived, only the write of the confirmation was left behind: the marker is stale. */
    @Test
    fun `a marker on the association that stays is cleared`() {
        val cleanup = PairingCleanup.afterReconcile(
            pendingId = 8,
            storedId = 8,
            keptId = 8,
            remainingIds = listOf(8),
        )

        assertTrue(cleanup.clearPending)
        assertFalse(cleanup.clearConfirmed)
    }

    @Test
    fun `a marker of a removed association is cleared`() {
        assertTrue(
            PairingCleanup.afterReconcile(
                pendingId = 9,
                storedId = null,
                keptId = null,
                remainingIds = emptyList(),
            ).clearPending,
        )
    }

    /** The removal failed: without the marker nothing would recognise the association as unconfirmed. */
    @Test
    fun `a marker of an association that is still there and not kept is left alone`() {
        assertFalse(
            PairingCleanup.afterReconcile(
                pendingId = 9,
                storedId = 8,
                keptId = 8,
                remainingIds = listOf(8, 9),
            ).clearPending,
        )
    }

    @Test
    fun `a stored association that no longer exists is forgotten, one that does is not`() {
        assertTrue(
            PairingCleanup.afterReconcile(
                pendingId = null,
                storedId = 8,
                keptId = null,
                remainingIds = listOf(9),
            ).clearConfirmed,
        )
        assertFalse(
            PairingCleanup.afterReconcile(
                pendingId = null,
                storedId = 8,
                keptId = 8,
                remainingIds = listOf(8),
            ).clearConfirmed,
        )
    }

    // Healing a confirmation that was reported in the state but never reached the disk.

    @Test
    fun `a confirmed press whose write was lost is healed`() {
        assertEquals(
            7,
            PairingCleanup.confirmationToHeal(
                lastConfirmation = ConfirmationOutcome.CONFIRMED,
                lastConfirmedAssociationId = 7,
                pendingMarkerId = 7,
                associationIds = listOf(7),
            ),
        )
    }

    /**
     * The case that makes the ID comparison necessary: a `CONFIRMED` outcome outlives the service and the
     * state cleanup, so a marker of a newly chosen association must not inherit it — nobody has pressed
     * anything on that one.
     */
    @Test
    fun `a confirmation of another remote does not heal a new association`() {
        assertNull(
            PairingCleanup.confirmationToHeal(
                lastConfirmation = ConfirmationOutcome.CONFIRMED,
                lastConfirmedAssociationId = 7,
                pendingMarkerId = 11,
                associationIds = listOf(11),
            ),
        )
    }

    @Test
    fun `nothing is healed without a confirmed press, without a marker or without the association`() {
        assertNull(
            PairingCleanup.confirmationToHeal(
                lastConfirmation = ConfirmationOutcome.TIMED_OUT,
                lastConfirmedAssociationId = null,
                pendingMarkerId = 7,
                associationIds = listOf(7),
            ),
        )
        assertNull(
            PairingCleanup.confirmationToHeal(
                lastConfirmation = ConfirmationOutcome.CONFIRMED,
                lastConfirmedAssociationId = 7,
                pendingMarkerId = null,
                associationIds = listOf(7),
            ),
        )
        assertNull(
            PairingCleanup.confirmationToHeal(
                lastConfirmation = ConfirmationOutcome.CONFIRMED,
                lastConfirmedAssociationId = 7,
                pendingMarkerId = 7,
                associationIds = emptyList(),
            ),
        )
    }

    /**
     * A leftover association is taken back while the user is already confirming a new one: the service holds
     * the connection that press has to arrive on, so stopping it here would make the new setup fail.
     */
    @Test
    fun `the service keeps running while another association is being confirmed`() {
        assertFalse(
            PairingCleanup.decide(
                associationId = 7,
                remainingIds = emptyList(),
                storedId = null,
                pendingId = 7,
                serviceRunning = true,
                confirmingId = 11,
            ).stopService,
        )
        // The confirmation of the association that is being taken back is a different matter: nothing is left.
        assertTrue(
            PairingCleanup.decide(
                associationId = 7,
                remainingIds = emptyList(),
                storedId = null,
                pendingId = 7,
                serviceRunning = true,
                confirmingId = 7,
            ).stopService,
        )
    }

    @Test
    fun `a service that is not running is not stopped`() {
        assertFalse(
            PairingCleanup.decide(
                associationId = 7,
                remainingIds = emptyList(),
                storedId = null,
                pendingId = null,
                serviceRunning = false,
            ).stopService,
        )
    }
}
