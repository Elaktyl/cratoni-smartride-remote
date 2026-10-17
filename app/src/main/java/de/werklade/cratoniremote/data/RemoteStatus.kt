package de.werklade.cratoniremote.data

import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.RemoteBattery
import de.werklade.cratoniremote.helmet.HelmetConnectionState
import de.werklade.cratoniremote.helmet.HelmetLights
import de.werklade.cratoniremote.protocol.RemoteCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A command that was received and, if mapped, executed, together with the wall clock time of reception.
 *
 * [atEpochMs] is milliseconds since the epoch ([System.currentTimeMillis]); it is only used for display,
 * never for measuring durations.
 */
data class LastCommand(val command: RemoteCommand, val atEpochMs: Long)

/**
 * How the last confirmation of a chosen remote ended ([PendingConfirmation]).
 *
 * Only [CONFIRMED] keeps the association; in every other case it is removed again, which is what the screen
 * says in its own words.
 */
enum class ConfirmationOutcome {

    /** A button press arrived on the chosen remote: it is the user's remote and is remembered for good. */
    CONFIRMED,

    /** No press within [CONFIRMATION_TIMEOUT_MS] — the normal way of saying "that was not my remote". */
    TIMED_OUT,

    /** The user cancelled the waiting. */
    CANCELLED,

    /**
     * Something on the way kept the confirmation from finishing — the connection the press had to arrive on
     * could not be held, or the safety net for a process death could not be written. The pairing is taken
     * back; the user tries again. Deliberately not [CANCELLED]: the user cancelled nothing.
     */
    FAILED,

    /**
     * Bluetooth was off when the waiting should have started, so no press could ever have arrived. Its own
     * outcome because the way out is a different one: switch Bluetooth on, then set up again.
     */
    BLUETOOTH_OFF,
}

/**
 * Everything the UI and the notification show about the remote (docs/architektur.md §8).
 *
 * Pure Kotlin, value-based equality, so [RemoteStatus] only emits on real changes.
 *
 * @param serviceRunning `true` between start and destruction of
 *   `de.werklade.cratoniremote.service.RemoteConnectionService`; while it is `false` the remote is not
 *   considered present by the companion device presence observation.
 * @param connection state of the single [de.werklade.cratoniremote.ble.CratoniBleManager] of that service
 * @param waitingForBluetooth `true` while the service is up but Bluetooth is switched off, so there is nothing
 *   to connect to until the adapter comes back; the service reconnects itself then. Deliberately separate from
 *   [connection]: it is not a state of the BLE manager but of the service around it, and it must not be shown
 *   as an error the user has to act on beyond switching Bluetooth on.
 * @param battery last valid battery measurement (percent, cell voltage, time of the measurement), `null` while
 *   unknown. It is read from the remote while connected and cleared when the connection ends, so it always
 *   belongs to the current connection.
 * @param helmet state of the SPP connection to the helmet
 *   ([de.werklade.cratoniremote.helmet.HelmetSppClient]), which the service opens as soon as the remote is
 *   connected
 * @param lights what the app believes the helmet's three lights are doing. A belief, not a measurement: the
 *   helmet acknowledges nothing (docs/architektur.md §13). It is reset together with the rest of this state, so
 *   nothing from a previous connection is shown.
 * @param pendingConfirmation the remote that was chosen in the association dialog and is waiting for its
 *   confirmation press, `null` whenever none is. While it is set, no received press is executed
 *   ([de.werklade.cratoniremote.service.dispatchAllowed]) and the service connects to that association only.
 *   It lives here, in the state shared by UI and service, because both sides need it: the UI counts the
 *   timeout, the service decides what to do with an arriving packet.
 * @param lastConfirmation how the last confirmation ended, `null` until one did; only for the screen. It is
 *   kept when the service stops, like [lastCommand], because the message belongs to the setup the user just
 *   went through and not to the connection.
 * @param lastConfirmedAssociationId the association [lastConfirmation] belongs to when it is
 *   [ConfirmationOutcome.CONFIRMED], `null` otherwise. Needed because "a press arrived" alone is not enough to
 *   write a confirmation to disk after the fact (`companion.CompanionSetup` heals a write that did not make
 *   it): without the ID, a `CONFIRMED` left over from an earlier remote could be applied to a different,
 *   never confirmed association.
 */
data class RemoteState(
    val serviceRunning: Boolean = false,
    val connection: BleConnectionState = BleConnectionState.Disconnected,
    val waitingForBluetooth: Boolean = false,
    val battery: RemoteBattery? = null,
    val helmet: HelmetConnectionState = HelmetConnectionState.Disconnected,
    val lights: HelmetLights = HelmetLights(),
    val lastCommand: LastCommand? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val lastConfirmation: ConfirmationOutcome? = null,
    val lastConfirmedAssociationId: Int? = null,
)

/**
 * Process-wide state of the remote, written by the foreground service and read by the UI
 * (docs/architektur.md §8: "`RemoteStatus` (StateFlow, geteilt von Service und UI)").
 *
 * A singleton on purpose: service and UI live in the same process but have no common lifecycle, and the
 * state must survive the UI being closed. Nothing here is persisted — whether a remote is set up is answered
 * by `CompanionDeviceManager.getMyAssociations()`, not by this class.
 *
 * All methods are thread-safe (they update a [MutableStateFlow]); the service calls them from its BLE thread.
 */
object RemoteStatus {

    private val _state = MutableStateFlow(RemoteState())

    /** Current state; [RemoteState.serviceRunning] is `false` until the service starts. */
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    /** Called when the foreground service has started. Keeps the last command for display. */
    fun onServiceStarted() = _state.update { it.copy(serviceRunning = true) }

    /**
     * Called when the foreground service is destroyed: connection state, battery level and the state of the
     * helmet and its lights no longer have a source, only [RemoteState.lastCommand] is kept.
     *
     * The outcome of the last confirmation is kept as well, and so is a confirmation that is somehow still
     * running: the service ends one before it is destroyed
     * (`service.RemoteConnectionService.onDestroy` — no service, no waiting, because nothing could receive a
     * press afterwards), so in practice only the outcome and the ID it belongs to arrive here. Both are the
     * explanation the screen gives for a pairing that was just taken back, exactly like [RemoteState.lastCommand].
     */
    fun onServiceStopped() = _state.update {
        RemoteState(
            lastCommand = it.lastCommand,
            pendingConfirmation = it.pendingConfirmation,
            lastConfirmation = it.lastConfirmation,
            lastConfirmedAssociationId = it.lastConfirmedAssociationId,
        )
    }

    /**
     * Called when the remote was forgotten (`companion.CompanionSetup.forget()`): unlike [onServiceStopped]
     * this drops [RemoteState.lastCommand] as well, because it belongs to a remote that is no longer set up —
     * and with it a running confirmation and its outcome ([RemoteState.pendingConfirmation],
     * [RemoteState.lastConfirmation]): after forgetting there is nothing left to confirm.
     *
     * @param keepConfirmationOutcome keeps [RemoteState.lastConfirmation], for the callers that clean up after
     *   a confirmation that just failed (`companion.CompanionSetup.reconcile`): the message why the pairing was
     *   taken back is the one thing the user still needs, exactly like [RemoteState.lastCommand] after a
     *   service stop. Forgetting the remote is the user's own doing and keeps nothing.
     */
    fun reset(keepConfirmationOutcome: Boolean = false) = _state.update {
        if (keepConfirmationOutcome) {
            RemoteState(
                lastConfirmation = it.lastConfirmation,
                lastConfirmedAssociationId = it.lastConfirmedAssociationId,
            )
        } else {
            RemoteState()
        }
    }

    fun onConnectionState(connection: BleConnectionState) = _state.update { it.copy(connection = connection) }

    /**
     * Called by the service when it starts waiting for Bluetooth to be switched on ([waiting] `true`) and when
     * it connects again after that ([waiting] `false`). See [RemoteState.waitingForBluetooth].
     */
    fun onWaitingForBluetooth(waiting: Boolean) = _state.update { it.copy(waitingForBluetooth = waiting) }

    /**
     * Called with every measurement of [de.werklade.cratoniremote.ble.CratoniBleManager.battery] and with `null`
     * when the connection ends, which is where the displayed value is cleared.
     */
    fun onBattery(battery: RemoteBattery?) = _state.update { it.copy(battery = battery) }

    /** Called with every state of [de.werklade.cratoniremote.helmet.HelmetSppClient.connectionState]. */
    fun onHelmetState(helmet: HelmetConnectionState) = _state.update { it.copy(helmet = helmet) }

    /**
     * Called by `action.HelmetLightExecutor` after every frame it sent, and when it forgets its state because a
     * fresh helmet connection stands.
     */
    fun onHelmetLights(lights: HelmetLights) = _state.update { it.copy(lights = lights) }

    /** Called for every valid packet, whether or not V1 maps an action to [command]. */
    fun onCommand(command: RemoteCommand, atEpochMs: Long = System.currentTimeMillis()) =
        _state.update { it.copy(lastCommand = LastCommand(command, atEpochMs)) }

    /**
     * Called by the UI when a remote was chosen in the association dialog and the confirmation press is being
     * waited for. Drops the outcome of an earlier attempt: the screen must not show "abgelaufen" next to a
     * running countdown.
     */
    fun onConfirmationStarted(pending: PendingConfirmation) = _state.update {
        it.copy(pendingConfirmation = pending, lastConfirmation = null, lastConfirmedAssociationId = null)
    }

    /**
     * Called by the service when a valid packet arrived on the remote that is being confirmed — any button
     * counts, including one this version maps no action to. From here on presses are executed again.
     *
     * @param associationId the association the press arrived on; kept in
     *   [RemoteState.lastConfirmedAssociationId] so a confirmation that could not be written can be written
     *   later without any doubt about which association it belonged to.
     */
    fun onConfirmed(associationId: Int) = _state.update {
        it.copy(
            pendingConfirmation = null,
            lastConfirmation = ConfirmationOutcome.CONFIRMED,
            lastConfirmedAssociationId = associationId,
        )
    }

    /**
     * Called when the confirmation ended without a press ([ConfirmationOutcome.TIMED_OUT],
     * [ConfirmationOutcome.CANCELLED], [ConfirmationOutcome.BLUETOOTH_OFF]). The caller removes the
     * association; this only ends the waiting and leaves the reason for the screen.
     */
    fun onConfirmationFailed(outcome: ConfirmationOutcome) = _state.update {
        it.copy(pendingConfirmation = null, lastConfirmation = outcome, lastConfirmedAssociationId = null)
    }
}
