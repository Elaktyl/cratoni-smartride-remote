package de.werklade.cratoniremote.companion

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.util.Log
import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.CratoniBleManager
import de.werklade.cratoniremote.data.RemoteState
import de.werklade.cratoniremote.data.RemoteStatus
import de.werklade.cratoniremote.service.RemoteConnectionService

/**
 * Receives the device presence events of the system and runs
 * [RemoteConnectionService] exactly while the remote is present (docs/architektur.md §3 "Betrieb").
 *
 * Bound by the platform only (manifest: `android.permission.BIND_COMPANION_DEVICE_SERVICE`,
 * action [CompanionDeviceService.SERVICE_INTERFACE]). Starting the foreground service from here is allowed
 * in the background because of `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`.
 *
 * Only the BLE events are relevant: the remote is not bonded, so `EVENT_BT_CONNECTED`/`EVENT_BT_DISCONNECTED`
 * (bonded Bluetooth devices) and the self-managed events cannot occur for it. The deprecated callbacks
 * `onDeviceAppeared`/`onDeviceDisappeared` are deliberately not overridden.
 *
 * This class does no BLE work itself; it only starts and stops the service, which is idempotent on both sides.
 */
class CratoniCompanionService : CompanionDeviceService() {

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val associationId = event.associationId
        when (val type = event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> {
                Log.i(TAG, "Presence: BLE_APPEARED (association $associationId)")
                RemoteConnectionService.start(this, associationId)
            }
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> onBleDisappeared(associationId)
            DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED -> {
                // Sent when the association is gone, e.g. after "Remote vergessen" or removal in the settings.
                Log.i(TAG, "Presence: ASSOCIATION_REMOVED (association $associationId)")
                RemoteConnectionService.stop(this)
            }
            else -> Log.i(TAG, "Presence: event $type ignored (association $associationId)")
        }
    }

    /**
     * "Disappeared" only stops the service while the remote is neither connected nor waited for nor being
     * confirmed.
     *
     * A connected BLE peripheral usually stops advertising, so the system can report it as gone although the
     * link is up and packets keep arriving; stopping then would cut a working connection. While
     * [de.werklade.cratoniremote.ble.BleConnectionState.Connected] the GATT stack decides instead: if the link
     * really drops, the manager's reconnect policy runs and ends in
     * [de.werklade.cratoniremote.ble.BleConnectionState.Failed], on which the service stops itself.
     *
     * The same holds while the service waits for Bluetooth
     * ([de.werklade.cratoniremote.data.RemoteState.waitingForBluetooth]): its receiver is the only place that
     * notices the adapter coming back, because the system sends no new `BLE_APPEARED` for a remote it never
     * reported as gone (device test 12.09.2026). Stopping the service here would kill exactly that receiver and
     * leave the app dead until the next association or app start.
     *
     * The third case is a confirmation that is running
     * ([de.werklade.cratoniremote.data.RemoteState.pendingConfirmation]): the connection to the chosen remote
     * exists only so the confirmation press can be received, the remote stops advertising while it stands, and
     * a `BLE_DISAPPEARED` arriving a few seconds later would stop the service and with it the only way to
     * confirm — the user would press a button and nothing would ever happen.
     */
    private fun onBleDisappeared(associationId: Int) {
        val state = RemoteStatus.state.value
        val keepAliveReason = keepAliveReason(state)
        if (keepAliveReason != null) {
            Log.i(TAG, "Presence: BLE_DISAPPEARED (association $associationId) not stopping, $keepAliveReason")
            return
        }
        Log.i(
            TAG,
            "Presence: BLE_DISAPPEARED (association $associationId), connection is ${state.connection} " +
                "and not waiting for Bluetooth, stopping",
        )
        RemoteConnectionService.stop(this)
    }

    companion object {
        private const val TAG = CratoniBleManager.TAG
    }
}

/**
 * Why `BLE_DISAPPEARED` must not stop [RemoteConnectionService] in [state], or `null` if it may.
 *
 * Pure Kotlin so the rule can be tested; the text goes into the log, so the device log shows which of the two
 * cases kept the service alive. See [CratoniCompanionService.onBleDisappeared] for the reasoning.
 */
internal fun keepAliveReason(state: RemoteState): String? = when {
    state.pendingConfirmation != null ->
        "a chosen remote is waiting for its confirmation press; only the service can receive it"
    state.connection == BleConnectionState.Connected ->
        "connection is up (a connected remote stops advertising); leaving it to the GATT stack"
    state.waitingForBluetooth ->
        "the service is waiting for Bluetooth and is the only place that notices it coming back"
    else -> null
}
