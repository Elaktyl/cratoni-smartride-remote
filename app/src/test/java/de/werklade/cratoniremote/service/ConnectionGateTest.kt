package de.werklade.cratoniremote.service

import de.werklade.cratoniremote.data.CONFIRMATION_TIMEOUT_MS
import de.werklade.cratoniremote.data.ConfirmationOutcome
import de.werklade.cratoniremote.data.PendingConfirmation
import de.werklade.cratoniremote.data.RemoteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [connectAllowed] is the lock that keeps the app off somebody else's remote: on a group ride several
 * identical `Remote control-XXXX` are in range, the system can report any association as present, and a
 * connection to the wrong one would take its button presses and switch its owner's helmet lights.
 */
class ConnectionGateTest {

    private val pending = PendingConfirmation(
        associationId = 7,
        label = "Remote control-3D2C",
        deadlineElapsedMs = 12_345_678L + CONFIRMATION_TIMEOUT_MS,
    )

    private val confirming = RemoteState(serviceRunning = true, pendingConfirmation = pending)

    private val idle = RemoteState(serviceRunning = true)

    @Test
    fun `while confirming only the association being confirmed is connected to`() {
        assertTrue(connectAllowed(confirming, confirmedId = null, requestedId = 7))
    }

    @Test
    fun `while confirming every other association is refused`() {
        listOf(8, 0, 99, RemoteConnectionService.NO_ASSOCIATION_ID).forEach { requestedId ->
            assertFalse("$requestedId", connectAllowed(confirming, confirmedId = null, requestedId = requestedId))
        }
    }

    /** Even an already confirmed remote waits: the one connection the remote allows belongs to the press. */
    @Test
    fun `while confirming the confirmed association is refused too`() {
        assertFalse(connectAllowed(confirming, confirmedId = 8, requestedId = 8))
    }

    /**
     * No confirmed remote: a presence event can only come from a left-over association nobody confirmed —
     * exactly what must not be connected to.
     */
    @Test
    fun `without a confirmed remote nothing is connected to`() {
        listOf(7, 8, RemoteConnectionService.NO_ASSOCIATION_ID).forEach { requestedId ->
            assertFalse("$requestedId", connectAllowed(idle, confirmedId = null, requestedId = requestedId))
        }
    }

    @Test
    fun `the confirmed association is connected to`() {
        assertTrue(connectAllowed(idle, confirmedId = 8, requestedId = 8))
    }

    @Test
    fun `another association than the confirmed one is refused`() {
        assertFalse(connectAllowed(idle, confirmedId = 8, requestedId = 9))
    }

    /** "The one the app knows" from the UI's restart after `BLUETOOTH_CONNECT` was granted. */
    @Test
    fun `the unspecified association means the confirmed one`() {
        assertTrue(
            connectAllowed(idle, confirmedId = 8, requestedId = RemoteConnectionService.NO_ASSOCIATION_ID),
        )
        assertFalse(
            connectAllowed(idle, confirmedId = null, requestedId = RemoteConnectionService.NO_ASSOCIATION_ID),
        )
    }

    // The verdict around the one-time migration, the case of the device test of 17.10.2026: a presence event
    // arrived after an update, before the user had ever opened the app, so nothing was confirmed yet.

    /** Nothing confirmed and the migration window still open: the service settles the pairing first. */
    @Test
    fun `an open migration window leads to settling the pairing instead of refusing`() {
        assertEquals(
            ConnectVerdict.RESOLVE_MIGRATION,
            connectDecision(idle, confirmedId = null, requestedId = 10, legacyMigrationDone = false),
        )
    }

    /**
     * The regression guard for the protection itself: once the window is closed, "nothing confirmed" means
     * "nobody ever confirmed this association" — a leftover, or somebody else's remote.
     */
    @Test
    fun `a closed migration window keeps the refusal`() {
        assertEquals(
            ConnectVerdict.REFUSE,
            connectDecision(idle, confirmedId = null, requestedId = 10, legacyMigrationDone = true),
        )
    }

    @Test
    fun `a confirmed remote connects without any migration`() {
        listOf(false, true).forEach { migrationDone ->
            assertEquals(
                "done=$migrationDone",
                ConnectVerdict.CONNECT,
                connectDecision(idle, confirmedId = 10, requestedId = 10, legacyMigrationDone = migrationDone),
            )
            // Another association than the confirmed one is refused, and no migration can change that:
            // something is confirmed, so the window is irrelevant.
            assertEquals(
                "done=$migrationDone",
                ConnectVerdict.REFUSE,
                connectDecision(idle, confirmedId = 10, requestedId = 11, legacyMigrationDone = migrationDone),
            )
        }
    }

    /**
     * While a confirmation runs, nothing is settled in the background: the user is answering the question
     * right now, and a reconcile is not what should answer it.
     */
    @Test
    fun `a running confirmation never leads to settling the pairing`() {
        assertEquals(
            ConnectVerdict.CONNECT,
            connectDecision(confirming, confirmedId = null, requestedId = 7, legacyMigrationDone = false),
        )
        assertEquals(
            ConnectVerdict.REFUSE,
            connectDecision(confirming, confirmedId = null, requestedId = 8, legacyMigrationDone = false),
        )
    }

    /** "The one the app knows" follows the same two steps. */
    @Test
    fun `the unspecified association also settles the pairing first`() {
        val requestedId = RemoteConnectionService.NO_ASSOCIATION_ID
        assertEquals(
            ConnectVerdict.RESOLVE_MIGRATION,
            connectDecision(idle, confirmedId = null, requestedId = requestedId, legacyMigrationDone = false),
        )
        assertEquals(
            ConnectVerdict.REFUSE,
            connectDecision(idle, confirmedId = null, requestedId = requestedId, legacyMigrationDone = true),
        )
        assertEquals(
            ConnectVerdict.CONNECT,
            connectDecision(idle, confirmedId = 10, requestedId = requestedId, legacyMigrationDone = true),
        )
    }

    /** A finished confirmation is no longer a reason to refuse the confirmed remote. */
    @Test
    fun `after a confirmation the gate opens for the confirmed association`() {
        val confirmed = RemoteState(serviceRunning = true, lastConfirmation = ConfirmationOutcome.CONFIRMED)

        assertTrue(connectAllowed(confirmed, confirmedId = 7, requestedId = 7))
        assertFalse(connectAllowed(confirmed, confirmedId = 7, requestedId = 8))
    }
}
