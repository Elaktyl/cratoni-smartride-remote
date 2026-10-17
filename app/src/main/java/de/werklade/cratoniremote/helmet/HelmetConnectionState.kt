package de.werklade.cratoniremote.helmet

/**
 * State of the SPP connection to the helmet, published by [HelmetSppClient.connectionState]. Pure Kotlin.
 *
 * Regular sequence: [Disconnected] → [Connecting] → [Connected]. [NotFound] is not an error the user has to
 * act on beyond pairing the helmet; a helmet that is switched off or out of range ends in [Failed] with
 * [HelmetFailure.CONNECT] and stays silent (decision of the project owner, 26.09.2026: no audible feedback,
 * log and screen only).
 */
sealed interface HelmetConnectionState {

    /** No bonded device matches [HelmetSppClient.HELMET_NAME_PREFIX], or more than one does. */
    data object NotFound : HelmetConnectionState

    /** Initial state, and the state after the socket was closed. */
    data object Disconnected : HelmetConnectionState

    /** `BluetoothSocket.connect()` is running (on the client's own thread). */
    data object Connecting : HelmetConnectionState

    /** Socket open; light frames are being written. */
    data object Connected : HelmetConnectionState

    /** Gave up; nothing is retried until the next [HelmetSppClient.connect] (which a button press triggers). */
    data class Failed(val reason: HelmetFailure) : HelmetConnectionState
}

/** Why the helmet connection failed. */
enum class HelmetFailure {

    /** No Bluetooth adapter, or Bluetooth is switched off. */
    BLUETOOTH_UNAVAILABLE,

    /** `BLUETOOTH_CONNECT` is not granted, or the system threw a `SecurityException`. */
    MISSING_PERMISSION,

    /** `connect()` failed — helmet switched off, out of range, or no SPP service record. */
    CONNECT,

    /** Writing a frame failed; the socket was closed. The next press tries to connect again. */
    SEND,
}
