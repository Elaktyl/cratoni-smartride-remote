package de.werklade.cratoniremote.data

import de.werklade.cratoniremote.R
import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.FailureReason
import de.werklade.cratoniremote.ble.GattStep
import de.werklade.cratoniremote.protocol.RemoteCommand

/**
 * A piece of text named by its string resource plus the arguments formatted into it — the text before anyone
 * looked it up.
 *
 * It exists so the two places that show the state of the remote can share the decision *which* text belongs
 * there without sharing a way to obtain it: `ui.RemoteScreen` resolves a spec with `stringResource`, the
 * foreground service notification with `Resources.getString` (`service.NotificationText`), and a unit test
 * with a fake [StringLookup]. Nothing here touches Android, so the decision stays covered by local tests.
 *
 * An argument is either a value already formatted for the language (a number, a time) or another [TextSpec],
 * which [resolve] looks up first — `remote_status_error` carries a `failure_*` text, and that one in turn can
 * carry a `gatt_step_*` text.
 *
 * @param id a string resource (`R.string.…`)
 */
data class TextSpec(val id: Int, val args: List<Any> = emptyList())

/**
 * Looks up the string resource [Int] and formats the arguments into it, i.e. what
 * `android.content.res.Resources.getString(int, Object...)` does.
 */
typealias StringLookup = (Int, Array<Any>) -> String

/** Resolves this spec, and every spec among its arguments, through [lookup]. */
fun TextSpec.resolve(lookup: StringLookup): String =
    lookup(id, args.map { if (it is TextSpec) it.resolve(lookup) else it }.toTypedArray())

/**
 * Which text says what about the remote — the one place that decides it, for the screen and for the
 * notification alike (docs/architektur.md §10).
 *
 * Only the wording differs between the two, never the choice: both call [remoteLine], the notification just
 * passes its own, longer resource for the waiting state.
 */
object StatusText {

    /**
     * State of the remote in one line.
     *
     * Without an association nothing is observed; with one but without a running service the system has not
     * reported the remote as present yet, which is the normal idle state (`remote_status_out_of_range`) and
     * not an error.
     *
     * A running confirmation (`remote_status_confirming`) comes right after "not set up" and before everything
     * else: it is the one state in which the user has something to do, and it is short-lived. The connection
     * to the chosen remote stands while it runs, so "Connected" would be true and useless.
     *
     * @param remoteConfigured whether an association exists (`CompanionDeviceManager.getMyAssociations()`)
     * @param waitingForBluetooth the resource for "Bluetooth is off and the service waits for it". The screen
     *   takes the default; the notification passes `R.string.notification_waiting_for_bluetooth`, which says
     *   it in a full sentence because it stands alone in the status bar without the card that explains it.
     */
    fun remoteLine(
        remoteConfigured: Boolean,
        state: RemoteState,
        waitingForBluetooth: Int = R.string.remote_status_waiting_for_bluetooth,
    ): TextSpec = when {
        !remoteConfigured -> TextSpec(R.string.remote_status_not_configured)
        state.pendingConfirmation != null -> TextSpec(R.string.remote_status_confirming)
        state.waitingForBluetooth -> TextSpec(waitingForBluetooth)
        !state.serviceRunning && state.connection == BleConnectionState.Disconnected ->
            TextSpec(R.string.remote_status_out_of_range)
        else -> connection(state.connection)
    }

    /** The connection state as the user reads it; the reconnect delay is cut down to whole seconds. */
    fun connection(connection: BleConnectionState): TextSpec = when (connection) {
        BleConnectionState.Disconnected -> TextSpec(R.string.remote_status_disconnected)
        BleConnectionState.Connecting -> TextSpec(R.string.remote_status_connecting)
        BleConnectionState.DiscoveringServices -> TextSpec(R.string.remote_status_discovering)
        BleConnectionState.EnablingNotifications -> TextSpec(R.string.remote_status_enabling_notifications)
        BleConnectionState.Connected -> TextSpec(R.string.remote_status_connected)
        is BleConnectionState.Reconnecting -> TextSpec(
            R.string.remote_status_reconnecting,
            listOf(connection.attempt, connection.maxAttempts, (connection.delayMs / 1000).toInt()),
        )
        is BleConnectionState.Failed -> TextSpec(R.string.remote_status_error, listOf(failure(connection.reason)))
    }

    /**
     * Why a connection attempt failed, in understandable terms.
     *
     * The GATT status code and the status a connection was lost with are deliberately not part of it: they say
     * nothing to the user and everything to the log, which has them ([de.werklade.cratoniremote.ble.BleLog]).
     */
    fun failure(reason: FailureReason): TextSpec = when (reason) {
        FailureReason.BluetoothAdapterMissing -> TextSpec(R.string.failure_bluetooth_adapter_missing)
        FailureReason.BluetoothDisabled -> TextSpec(R.string.failure_bluetooth_disabled)
        FailureReason.MissingPermission -> TextSpec(R.string.failure_missing_permission)
        is FailureReason.GattError -> TextSpec(R.string.failure_gatt_error, listOf(gattStep(reason.step)))
        is FailureReason.ConnectionLost -> TextSpec(R.string.failure_connection_lost)
        is FailureReason.OperationNotStarted ->
            TextSpec(R.string.failure_operation_not_started, listOf(gattStep(reason.step)))
        is FailureReason.Timeout -> TextSpec(R.string.failure_timeout, listOf(gattStep(reason.step)))
        is FailureReason.DeviceUnsuitable -> TextSpec(R.string.failure_device_unsuitable, listOf(reason.missing))
    }

    /** The step of the connection setup a failure happened in; always named inside a `failure_*` text. */
    fun gattStep(step: GattStep): TextSpec = when (step) {
        GattStep.CONNECT -> TextSpec(R.string.gatt_step_connect)
        GattStep.DISCOVER_SERVICES -> TextSpec(R.string.gatt_step_discover_services)
        GattStep.ENABLE_NOTIFICATIONS -> TextSpec(R.string.gatt_step_enable_notifications)
    }

    /** What a button of the remote does, not what it is called on the device. */
    fun command(command: RemoteCommand): TextSpec = when (command) {
        RemoteCommand.PLAY_PAUSE -> TextSpec(R.string.command_play_pause)
        RemoteCommand.NEXT -> TextSpec(R.string.command_next)
        RemoteCommand.PREVIOUS -> TextSpec(R.string.command_previous)
        RemoteCommand.VOLUME_UP -> TextSpec(R.string.command_volume_up)
        RemoteCommand.VOLUME_DOWN -> TextSpec(R.string.command_volume_down)
        RemoteCommand.CALL -> TextSpec(R.string.command_call)
        RemoteCommand.WALKIE_TALKIE -> TextSpec(R.string.command_walkie_talkie)
        RemoteCommand.TURN_LEFT -> TextSpec(R.string.command_turn_left)
        RemoteCommand.TURN_RIGHT -> TextSpec(R.string.command_turn_right)
        RemoteCommand.REAR_LIGHT -> TextSpec(R.string.command_rear_light)
        RemoteCommand.UNKNOWN -> TextSpec(R.string.command_unknown)
    }
}
