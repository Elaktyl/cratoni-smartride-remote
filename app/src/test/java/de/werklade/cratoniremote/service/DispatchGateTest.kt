package de.werklade.cratoniremote.service

import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.data.ConfirmationOutcome
import de.werklade.cratoniremote.data.PendingConfirmation
import de.werklade.cratoniremote.data.RemoteState
import de.werklade.cratoniremote.data.RemoteStatus
import de.werklade.cratoniremote.protocol.RemoteCommand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule behind [RemoteConnectionService]'s handling of a packet while a confirmation is running. */
class DispatchGateTest {

    private val pending = PendingConfirmation(
        associationId = 7,
        label = "Remote control-3D2C",
        deadlineElapsedMs = 12_345_678L + 30_000L,
    )

    @After
    fun tearDown() = RemoteStatus.reset()

    @Test
    fun `a running confirmation holds every press back`() {
        assertFalse(
            dispatchAllowed(
                RemoteState(
                    serviceRunning = true,
                    connection = BleConnectionState.Connected,
                    pendingConfirmation = pending,
                ),
            ),
        )
    }

    @Test
    fun `without a confirmation presses are dispatched`() {
        assertTrue(dispatchAllowed(RemoteState(serviceRunning = true, connection = BleConnectionState.Connected)))
        // Even after one that failed: the association is gone, nothing is held back any more.
        assertTrue(
            dispatchAllowed(
                RemoteState(serviceRunning = true, lastConfirmation = ConfirmationOutcome.TIMED_OUT),
            ),
        )
    }

    /** The sequence of the real flow: waiting, press, normal operation. */
    @Test
    fun `the confirming press itself is the last one that is held back`() {
        RemoteStatus.onServiceStarted()
        RemoteStatus.onConfirmationStarted(pending)
        assertFalse(dispatchAllowed(RemoteStatus.state.value))

        // The service shows the press and ends the waiting with it.
        RemoteStatus.onCommand(RemoteCommand.UNKNOWN, atEpochMs = 1_789_209_296_000L)
        RemoteStatus.onConfirmed(pending.associationId)

        assertTrue(dispatchAllowed(RemoteStatus.state.value))
        val state = RemoteStatus.state.value
        assertNull(state.pendingConfirmation)
        assertEquals(ConfirmationOutcome.CONFIRMED, state.lastConfirmation)
        assertEquals(7, state.lastConfirmedAssociationId)
        // Shown as the last command although it triggered no action.
        assertEquals(RemoteCommand.UNKNOWN, state.lastCommand?.command)
    }
}
