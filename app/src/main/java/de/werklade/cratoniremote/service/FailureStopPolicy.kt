package de.werklade.cratoniremote.service

import de.werklade.cratoniremote.ble.FailureReason
import de.werklade.cratoniremote.ble.ReconnectPolicy

/**
 * Decides what [RemoteConnectionService] does when [de.werklade.cratoniremote.ble.BleConnectionState.Failed] is
 * reached, i.e. after [ReconnectPolicy] gave up. Pure Kotlin.
 *
 * Three cases, see [FailureAction]. The split between "a retry could work" and "a retry cannot" is exactly
 * [ReconnectPolicy.isRetryable], which is why this delegates instead of listing the reasons a second time;
 * [FailureReason.BluetoothDisabled] is then taken out of the second group because it is the one reason that
 * resolves itself without the user touching the app — and the service is the only place that notices it
 * (verified on the device 12.09.2026: after Bluetooth came back the system sent no new `BLE_APPEARED`, because
 * it had never reported the remote as gone).
 */
internal object FailureStopPolicy {

    private val reconnectPolicy = ReconnectPolicy()

    /** What the service does after [de.werklade.cratoniremote.ble.BleConnectionState.Failed]. */
    enum class FailureAction {

        /**
         * Stop the service: the next attempt can succeed, so the next presence event starts it fresh. That also
         * removes the notification instead of leaving a permanent error in the status bar.
         */
        STOP,

        /**
         * Keep the service up without a connection attempt and wait for [android.bluetooth.BluetoothAdapter]
         * `STATE_ON`, on which the service connects again by itself. Stopping here would mean nobody is
         * listening for that broadcast any more.
         */
        WAIT_FOR_BLUETOOTH,

        /**
         * Keep the service up and keep the error visible: an immediate restart would fail the same way within
         * seconds and turn every presence event into a start-stop cycle. This is the state the user has to act
         * on (missing adapter, missing `BLUETOOTH_CONNECT`, unsuitable device).
         */
        KEEP_ERROR_VISIBLE,
    }

    /** The action for [reason]; see [FailureAction]. */
    fun actionFor(reason: FailureReason): FailureAction = when {
        reconnectPolicy.isRetryable(reason) -> FailureAction.STOP
        reason == FailureReason.BluetoothDisabled -> FailureAction.WAIT_FOR_BLUETOOTH
        else -> FailureAction.KEEP_ERROR_VISIBLE
    }
}
