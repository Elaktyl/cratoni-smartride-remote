package de.werklade.cratoniremote.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind [CompanionSetup.reconcile].
 *
 * The one direction that must never happen is adoption of an association nobody confirmed, so most of these
 * tests are about exactly that.
 */
class PairingReconcilerTest {

    /** Fresh install or an app that has reconciled before; the default for the cases that are not about it. */
    private fun decide(
        storedId: Int? = null,
        pendingId: Int? = null,
        associationIds: List<Int> = emptyList(),
        legacyMigrationDone: Boolean = false,
        confirmingId: Int? = null,
    ) = PairingReconciler.decide(storedId, pendingId, associationIds, legacyMigrationDone, confirmingId)

    @Test
    fun `nothing stored and no association leaves nothing but the closed window`() {
        assertEquals(PairingReconciler.Decision(markMigrationDone = true), decide())
    }

    /**
     * The case of an update: the install was set up with a version that knew no confirmation, so there is no
     * pending marker either — that is what tells this case apart from an unconfirmed association.
     */
    @Test
    fun `a single association without a stored one and without a marker is adopted once`() {
        val decision = decide(associationIds = listOf(7))

        assertEquals(7, decision.keepId)
        assertEquals(7, decision.adoptId)
        assertTrue(decision.removeIds.isEmpty())
        assertFalse(decision.clearStored)
        assertFalse(decision.clearPending)
        assertTrue(decision.markMigrationDone)
    }

    /**
     * The heart of it: once the one-time window is closed, an association nobody confirmed is removed, no
     * matter how it came to be the only one. That covers every way the pending marker can go missing — an
     * unwritable preferences file, a cancelled coroutine, a `disassociate` that failed earlier.
     */
    @Test
    fun `after the migration a single unconfirmed association is removed, not adopted`() {
        val decision = decide(associationIds = listOf(7), legacyMigrationDone = true)

        assertNull(decision.keepId)
        assertNull(decision.adoptId)
        assertEquals(listOf(7), decision.removeIds)
        assertFalse(decision.markMigrationDone)
    }

    /**
     * The process died between the dialog and the press. Adopting here would hand the user whatever remote
     * they happened to tap in the list — possibly a fellow rider's. It goes, and so does the marker.
     */
    @Test
    fun `the association the marker points to is removed instead of adopted`() {
        val decision = decide(pendingId = 7, associationIds = listOf(7))

        assertNull(decision.keepId)
        assertNull(decision.adoptId)
        assertEquals(listOf(7), decision.removeIds)
        assertTrue(decision.clearPending)
        assertFalse(decision.clearStored)
    }

    /** A marker for an association that is already gone is stale and must not stay in the store. */
    @Test
    fun `a marker without its association is cleared`() {
        assertEquals(
            PairingReconciler.Decision(clearPending = true, markMigrationDone = true),
            decide(pendingId = 7),
        )
    }

    /**
     * The press arrived but the confirmation could not be written in one go (the marker survived): the stored
     * ID is what counts, so the association stays and only the marker goes.
     */
    @Test
    fun `a marker on the confirmed association keeps it and clears the marker`() {
        assertEquals(
            PairingReconciler.Decision(keepId = 8, clearPending = true, markMigrationDone = true),
            decide(storedId = 8, pendingId = 8, associationIds = listOf(8)),
        )
    }

    /** The marker belongs to a second, unconfirmed association: the confirmed one is untouched. */
    @Test
    fun `a marker on another association does not endanger the confirmed one`() {
        val decision = decide(storedId = 8, pendingId = 9, associationIds = listOf(8, 9))

        assertEquals(8, decision.keepId)
        assertEquals(listOf(9), decision.removeIds)
        assertTrue(decision.clearPending)
    }

    /**
     * Two associations and nothing stored: they look alike (`Remote control-XXXX`), so nothing says which one
     * the user meant. Both go, the user sets up again and confirms with a button press.
     */
    @Test
    fun `several associations without a stored one are all removed`() {
        val decision = decide(associationIds = listOf(7, 8))

        assertNull(decision.keepId)
        assertNull(decision.adoptId)
        assertEquals(listOf(7, 8), decision.removeIds)
        assertFalse(decision.clearStored)
    }

    @Test
    fun `the stored association stays and the others go`() {
        val decision = decide(storedId = 8, associationIds = listOf(7, 8, 9), legacyMigrationDone = true)

        assertEquals(8, decision.keepId)
        assertNull(decision.adoptId)
        assertEquals(listOf(7, 9), decision.removeIds)
        assertFalse(decision.clearStored)
    }

    @Test
    fun `the stored association alone needs no change`() {
        assertEquals(
            PairingReconciler.Decision(keepId = 8),
            decide(storedId = 8, associationIds = listOf(8), legacyMigrationDone = true),
        )
    }

    /** Removed in the system settings: the ID must not stay in the store, see [PairingReconciler.Decision]. */
    @Test
    fun `a stored association that is gone is dropped`() {
        assertEquals(
            PairingReconciler.Decision(clearStored = true),
            decide(storedId = 8, legacyMigrationDone = true),
        )
    }

    @Test
    fun `an orphaned stored association does not keep a single other one from being adopted`() {
        val decision = decide(storedId = 8, associationIds = listOf(11))

        assertEquals(11, decision.keepId)
        assertEquals(11, decision.adoptId)
        assertTrue(decision.removeIds.isEmpty())
        // Not needed: writing the adopted ID replaces the stored one.
        assertFalse(decision.clearStored)
    }

    @Test
    fun `an orphaned stored association with several others removes everything`() {
        assertEquals(
            PairingReconciler.Decision(
                removeIds = listOf(11, 12),
                clearStored = true,
                markMigrationDone = true,
            ),
            decide(storedId = 8, associationIds = listOf(11, 12)),
        )
    }

    /**
     * The regression this guards: the reconcile runs on every refresh, and one of those refreshes happens in
     * the same moment the association dialog returns. If it removed the association the user has just chosen,
     * the setup would fail before the user could press anything — which is exactly what happened before the
     * confirmation was put into the state synchronously and handed to this rule.
     */
    @Test
    fun `the association being confirmed is never removed and never adopted`() {
        val decision = decide(associationIds = listOf(7), confirmingId = 7)

        assertTrue(decision.removeIds.isEmpty())
        assertNull(decision.adoptId)
        assertNull(decision.keepId)
    }

    /** Its marker belongs to the running confirmation and must survive it. */
    @Test
    fun `the marker of the association being confirmed is kept`() {
        assertFalse(decide(pendingId = 7, associationIds = listOf(7), confirmingId = 7).clearPending)
    }

    /** Leftovers are still cleaned up while a confirmation runs — just not the one being confirmed. */
    @Test
    fun `other associations are still removed while a confirmation runs`() {
        val decision = decide(associationIds = listOf(7, 8, 9), confirmingId = 8, legacyMigrationDone = true)

        assertEquals(listOf(7, 9), decision.removeIds)
        assertNull(decision.keepId)
    }

    /** An already confirmed remote is not removed either, confirmation or not. */
    @Test
    fun `a confirmed association survives a running confirmation of another one`() {
        val decision = decide(storedId = 8, associationIds = listOf(7, 8), confirmingId = 7)

        assertEquals(8, decision.keepId)
        assertTrue(decision.removeIds.isEmpty())
    }

    /** Invariants every caller relies on, over every combination that can occur. */
    @Test
    fun `the decision never removes what it keeps and never adopts something unconfirmed`() {
        val ids = listOf(emptyList(), listOf(7), listOf(7, 8), listOf(7, 8, 9))
        val stored = listOf(null, 7, 8, 99)
        val pending = listOf(null, 7, 8, 99)
        val confirming = listOf(null, 7, 9)
        listOf(false, true).forEach { migrationDone ->
            stored.forEach { storedId ->
                pending.forEach { pendingId ->
                    ids.forEach { associationIds ->
                        confirming.forEach { confirmingId ->
                        val decision = decide(storedId, pendingId, associationIds, migrationDone, confirmingId)
                        val label = "stored=$storedId, pending=$pendingId, ids=$associationIds, " +
                            "done=$migrationDone, confirming=$confirmingId"
                        assertFalse(label, decision.keepId != null && decision.keepId in decision.removeIds)
                        assertTrue(label, decision.adoptId == null || decision.adoptId == decision.keepId)
                        assertFalse(label, decision.adoptId != null && decision.clearStored)
                        assertTrue(label, decision.removeIds.all { it in associationIds })
                        // An association that was never confirmed must never become the remote.
                        assertTrue(label, decision.adoptId == null || decision.adoptId != pendingId)
                        // Adoption is a one-time migration, not a rule.
                        assertTrue(label, decision.adoptId == null || !migrationDone)
                        // The association being confirmed is off limits, in every respect.
                        assertFalse(label, confirmingId != null && confirmingId in decision.removeIds)
                        assertTrue(label, decision.adoptId == null || decision.adoptId != confirmingId)
                        assertTrue(label, confirmingId == null || decision.adoptId == null)
                        // No marker may survive a reconcile, unless it belongs to the running confirmation.
                        assertEquals(
                            label,
                            pendingId != null && pendingId != confirmingId,
                            decision.clearPending,
                        )
                        // The window closes on the first run and stays closed.
                        assertEquals(label, !migrationDone, decision.markMigrationDone)
                        }
                    }
                }
            }
        }
    }
}
