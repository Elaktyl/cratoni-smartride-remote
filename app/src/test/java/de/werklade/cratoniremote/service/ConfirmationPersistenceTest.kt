package de.werklade.cratoniremote.service

import de.werklade.cratoniremote.companion.ConfirmedRemoteWriter
import de.werklade.cratoniremote.data.CONFIRMATION_TIMEOUT_MS
import de.werklade.cratoniremote.data.ConfirmationOutcome
import de.werklade.cratoniremote.data.PendingConfirmation
import de.werklade.cratoniremote.data.RemoteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the **service** persists a confirmation press, not the UI: the UI can be gone the moment the press
 * arrives, and the chosen remote would then be taken back although the user confirmed it.
 */
class ConfirmationPersistenceTest {

    /** Records what the service would write; stands in for `companion.ConfirmedRemoteStore`. */
    private class FakeConfirmedRemoteWriter : ConfirmedRemoteWriter {
        val confirmed = mutableListOf<Int>()
        override fun confirm(associationId: Int) {
            confirmed += associationId
        }
    }

    private val writer = FakeConfirmedRemoteWriter()

    private val pending = PendingConfirmation(
        associationId = 7,
        label = "Remote control-3D2C",
        deadlineElapsedMs = 12_345_678L + CONFIRMATION_TIMEOUT_MS,
    )

    @Test
    fun `the press of a running confirmation is persisted`() {
        val confirmed = persistConfirmation(RemoteState(serviceRunning = true, pendingConfirmation = pending), writer)

        assertEquals(7, confirmed)
        assertEquals(listOf(7), writer.confirmed)
    }

    @Test
    fun `without a running confirmation nothing is persisted`() {
        assertNull(persistConfirmation(RemoteState(serviceRunning = true), writer))
        assertNull(
            persistConfirmation(
                RemoteState(serviceRunning = true, lastConfirmation = ConfirmationOutcome.TIMED_OUT),
                writer,
            ),
        )

        assertTrue(writer.confirmed.isEmpty())
    }

    /**
     * Two presses in quick succession, or a repeated attempt after a failed write, must not make a difference:
     * the same association is written again and nothing else happens.
     */
    @Test
    fun `persisting the same confirmation twice writes the same association`() {
        val state = RemoteState(serviceRunning = true, pendingConfirmation = pending)

        persistConfirmation(state, writer)
        persistConfirmation(state, writer)

        assertEquals(listOf(7, 7), writer.confirmed)
    }
}
