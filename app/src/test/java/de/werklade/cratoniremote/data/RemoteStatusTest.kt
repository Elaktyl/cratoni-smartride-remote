package de.werklade.cratoniremote.data

import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.RemoteBattery
import de.werklade.cratoniremote.helmet.HelmetConnectionState
import de.werklade.cratoniremote.helmet.HelmetLights
import de.werklade.cratoniremote.helmet.RearLight
import de.werklade.cratoniremote.protocol.RemoteCommand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RemoteStatus] is a process-wide singleton, so every test leaves it empty again ([tearDown]).
 *
 * Only the two states that survive something are checked here; the rest is plain `copy()`.
 */
class RemoteStatusTest {

    private val pending = PendingConfirmation(
        associationId = 7,
        label = "Remote control-3D2C",
        deadlineElapsedMs = 12_345_678L + CONFIRMATION_TIMEOUT_MS,
    )

    @After
    fun tearDown() = RemoteStatus.reset()

    /**
     * The last command is what the screen still shows after the service is gone; that is on purpose. The state of
     * the helmet and of its lights is not: it belongs to an SPP connection that no longer exists, and a line
     * saying "Blinker links an" about a helmet nothing is connected to would be a lie.
     */
    @Test
    fun `stopping the service keeps the last command but nothing else`() {
        RemoteStatus.onServiceStarted()
        RemoteStatus.onConnectionState(BleConnectionState.Connected)
        RemoteStatus.onBattery(RemoteBattery(percent = 80, millivolts = 3_906, atEpochMs = 1_789_209_296_000L))
        RemoteStatus.onHelmetState(HelmetConnectionState.Connected)
        RemoteStatus.onHelmetLights(HelmetLights(left = true, rear = RearLight.BLINKING))
        RemoteStatus.onCommand(RemoteCommand.NEXT, atEpochMs = 1_789_209_296_000L)

        RemoteStatus.onServiceStopped()

        val state = RemoteStatus.state.value
        assertEquals(LastCommand(RemoteCommand.NEXT, 1_789_209_296_000L), state.lastCommand)
        assertEquals(HelmetConnectionState.Disconnected, state.helmet)
        assertEquals(HelmetLights(), state.lights)
        assertEquals(RemoteState(lastCommand = state.lastCommand), state)
    }

    /**
     * The confirmation belongs to the setup in the UI, which counts its timeout; a service that stops in
     * between — for instance because the connection to the chosen remote failed — must not take the countdown
     * and the cancel button with it.
     */
    @Test
    fun `stopping the service keeps a running confirmation`() {
        RemoteStatus.onServiceStarted()
        RemoteStatus.onConfirmationStarted(pending)

        RemoteStatus.onServiceStopped()

        assertEquals(pending, RemoteStatus.state.value.pendingConfirmation)
    }

    /** Forgetting the remote must not leave a command of a remote that is no longer set up on the screen. */
    @Test
    fun `reset drops the last command as well`() {
        RemoteStatus.onServiceStarted()
        RemoteStatus.onCommand(RemoteCommand.PLAY_PAUSE, atEpochMs = 1_789_209_296_000L)

        RemoteStatus.reset()

        assertNull(RemoteStatus.state.value.lastCommand)
        assertEquals(RemoteState(), RemoteStatus.state.value)
    }

    /** After "Remote vergessen" there is nothing left to confirm and no outcome worth showing. */
    @Test
    fun `reset drops a running confirmation and its outcome`() {
        RemoteStatus.onConfirmationStarted(pending)
        RemoteStatus.onConfirmed(pending.associationId)

        RemoteStatus.reset()

        assertNull(RemoteStatus.state.value.pendingConfirmation)
        assertNull(RemoteStatus.state.value.lastConfirmation)
        assertEquals(RemoteState(), RemoteStatus.state.value)
    }

    /**
     * The cleanup after a confirmation that failed (`companion.CompanionSetup.reconcile`) clears everything
     * but the reason: it is the only explanation the user gets for a pairing that was just taken back.
     */
    @Test
    fun `reset can keep the outcome of the last confirmation`() {
        RemoteStatus.onServiceStarted()
        RemoteStatus.onConfirmationStarted(pending)
        RemoteStatus.onConfirmationFailed(ConfirmationOutcome.TIMED_OUT)
        RemoteStatus.onCommand(RemoteCommand.NEXT, atEpochMs = 1_789_209_296_000L)

        RemoteStatus.reset(keepConfirmationOutcome = true)

        assertEquals(
            RemoteState(lastConfirmation = ConfirmationOutcome.TIMED_OUT),
            RemoteStatus.state.value,
        )
    }

    /**
     * The confirmed association is remembered with the outcome: a confirmation whose write was lost can only
     * be repaired later if it is beyond doubt which association the press belonged to
     * (`companion.PairingCleanup.confirmationToHeal`).
     */
    @Test
    fun `a confirmed press remembers which association it belonged to`() {
        RemoteStatus.onConfirmationStarted(pending)

        RemoteStatus.onConfirmed(pending.associationId)

        assertEquals(ConfirmationOutcome.CONFIRMED, RemoteStatus.state.value.lastConfirmation)
        assertEquals(pending.associationId, RemoteStatus.state.value.lastConfirmedAssociationId)
    }

    @Test
    fun `a new attempt and a failed one drop the confirmed association`() {
        RemoteStatus.onConfirmed(7)
        RemoteStatus.onConfirmationStarted(pending.copy(associationId = 8))
        assertNull(RemoteStatus.state.value.lastConfirmedAssociationId)

        RemoteStatus.onConfirmed(8)
        RemoteStatus.onConfirmationFailed(ConfirmationOutcome.FAILED)
        assertNull(RemoteStatus.state.value.lastConfirmedAssociationId)
    }

    /** A new attempt must not show the outcome of the previous one next to its countdown. */
    @Test
    fun `starting a confirmation clears the previous outcome`() {
        RemoteStatus.onConfirmationStarted(pending)
        RemoteStatus.onConfirmationFailed(ConfirmationOutcome.TIMED_OUT)

        RemoteStatus.onConfirmationStarted(pending.copy(associationId = 8))

        assertEquals(8, RemoteStatus.state.value.pendingConfirmation?.associationId)
        assertNull(RemoteStatus.state.value.lastConfirmation)
    }

    @Test
    fun `a failed confirmation ends the waiting and keeps the reason`() {
        RemoteStatus.onConfirmationStarted(pending)

        RemoteStatus.onConfirmationFailed(ConfirmationOutcome.CANCELLED)

        assertNull(RemoteStatus.state.value.pendingConfirmation)
        assertEquals(ConfirmationOutcome.CANCELLED, RemoteStatus.state.value.lastConfirmation)
    }
}
