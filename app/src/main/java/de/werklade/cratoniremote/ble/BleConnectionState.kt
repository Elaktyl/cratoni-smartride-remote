package de.werklade.cratoniremote.ble

/**
 * Connection state published by [CratoniBleManager.connectionState]. Pure Kotlin.
 *
 * Regular sequence: [Disconnected] → [Connecting] → [DiscoveringServices] → [EnablingNotifications] → [Connected].
 * Enabling Battery Level notifications and reading the PnP ID happen while [Connected] and do not change the state.
 */
sealed interface BleConnectionState {

    /** No connection, no pending attempt. Initial state and state after [CratoniBleManager.disconnect]. */
    data object Disconnected : BleConnectionState

    /** `connectGatt` issued, waiting for the link. */
    data object Connecting : BleConnectionState

    /** Link established, service discovery running. */
    data object DiscoveringServices : BleConnectionState

    /** FFF0/FFF4 found, FFF4 notifications are being enabled (CCCD write). */
    data object EnablingNotifications : BleConnectionState

    /** FFF4 notifications enabled; packets are delivered. */
    data object Connected : BleConnectionState

    /**
     * Waiting [delayMs] before reconnect attempt [attempt] of [maxAttempts] after [cause].
     * The attempt itself runs through [Connecting], [DiscoveringServices], … again.
     */
    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val cause: FailureReason,
    ) : BleConnectionState

    /** Given up; nothing is scheduled until the next [CratoniBleManager.connect]. */
    data class Failed(val reason: FailureReason) : BleConnectionState
}

/** GATT step a [FailureReason] refers to. */
enum class GattStep {
    CONNECT,
    DISCOVER_SERVICES,
    ENABLE_NOTIFICATIONS,
}

/** Why a connection attempt failed or an established connection ended without [CratoniBleManager.disconnect]. */
sealed interface FailureReason {

    /** The device has no Bluetooth adapter. */
    data object BluetoothAdapterMissing : FailureReason

    /** Bluetooth is switched off. */
    data object BluetoothDisabled : FailureReason

    /** `BLUETOOTH_CONNECT` is not granted, or the system threw a [SecurityException]. */
    data object MissingPermission : FailureReason

    /** A GATT callback reported the non-zero [status] (GATT/HCI status code, e.g. 133) for [step]. */
    data class GattError(val step: GattStep, val status: Int) : FailureReason

    /**
     * The stack reported the link as disconnected without a preceding [CratoniBleManager.disconnect]: after the
     * link had been established, or while connecting with status 0. [status] as reported (may be 0).
     */
    data class ConnectionLost(val status: Int) : FailureReason

    /** The Android API call for [step] returned a failure or threw, so the step never started. */
    data class OperationNotStarted(val step: GattStep) : FailureReason

    /** [step] did not complete within its timeout. */
    data class Timeout(val step: GattStep) : FailureReason

    /** The connected device lacks a required GATT element, e.g. `"service FFF0"`. */
    data class DeviceUnsuitable(val missing: String) : FailureReason
}
