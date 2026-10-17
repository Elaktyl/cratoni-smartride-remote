package de.werklade.cratoniremote.service

import de.werklade.cratoniremote.helmet.HelmetConnectionState
import de.werklade.cratoniremote.helmet.HelmetSppClient

/**
 * What a classic Bluetooth ACL broadcast says about the device behind it; the two fields
 * [android.bluetooth.BluetoothDevice.getAddress] and [android.bluetooth.BluetoothDevice.getName] the service
 * reads from the broadcast extra, as plain values so the rules below stay pure Kotlin.
 *
 * Both are nullable because both can be: a name is unknown until the stack has it, and reading either needs
 * `BLUETOOTH_CONNECT` (`service.RemoteConnectionService.aclDevice`).
 */
internal data class AclDevice(val address: String?, val name: String?)

/**
 * Whether an ACL event belongs to the helmet. Pure Kotlin, so the rule is unit tested — it decides whether the
 * app opens a socket because of somebody else's headset.
 *
 * Same rule [HelmetSppClient] uses to find the helmet, in the order that can be trusted:
 *  * the address of the device the client itself resolved among the bonded devices
 *    ([HelmetSppClient.knownAddress]). That is the one device [HelmetSppClient.connect] would talk to, so
 *    anything else — even a second helmet of the same model — is not worth a connection attempt.
 *  * only if the client has not resolved one yet (no [connect][HelmetSppClient.connect] ran, or the bonding
 *    happened afterwards): the name prefix, [HelmetSppClient.HELMET_NAME_PREFIX]. The same comparison as
 *    `HelmetSppClient.findHelmet`, `ignoreCase` included.
 *
 * No address is ever hard-coded here: the only address this can match is the one the stack reported for a
 * bonded device at runtime.
 */
internal fun isHelmetDevice(device: AclDevice, helmetAddress: String?, namePrefix: String): Boolean = when {
    helmetAddress != null -> device.address?.equals(helmetAddress, ignoreCase = true) == true
    device.name != null -> device.name.startsWith(namePrefix, ignoreCase = true)
    else -> false
}

/**
 * Whether [android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED] for [device] must open the SPP connection
 * to the helmet. Pure Kotlin for the same reason as [isHelmetDevice] and [connectAllowed].
 *
 * The case it exists for (device test 17.10.2026): the helmet is switched on after the remote is already
 * connected. Until then the SPP connection was only attempted in the moment the remote connected, so a helmet
 * that was off at that point stayed unreachable until a light button was pressed — and that press was lost
 * ([HelmetSppClient.send]). Switching the helmet on makes it connect to the phone as a classic Bluetooth device
 * (headset/hands-free), and that is the event this answers.
 *
 * All of these must hold:
 *  * the event belongs to the helmet ([isHelmetDevice]).
 *  * no confirmation is running ([dispatchAllowed] of `service.DispatchGate`). The remote whose press is being
 *    waited for may not be the user's at all, and the helmet is deliberately left alone until the press
 *    confirms it — the same rule the "remote connected" path follows.
 *  * the helmet is neither connected nor connecting. [HelmetSppClient.connect] is idempotent, so this is not
 *    about safety but about not logging an attempt for a socket that already stands.
 *  * the remote is connected. Without it there is no light button to serve, and the service is on its way out
 *    anyway.
 *
 * [android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED] never leads to a connection; the service uses
 * [isHelmetDevice] alone for that event.
 *
 * @param helmetAddress [HelmetSppClient.knownAddress]
 * @param namePrefix [HelmetSppClient.HELMET_NAME_PREFIX]
 * @param helmetState [HelmetSppClient.connectionState]
 * @param dispatchAllowed [dispatchAllowed] for the current `data.RemoteState`
 * @param remoteConnected `ble.CratoniBleManager.connectionState` is `ble.BleConnectionState.Connected`
 */
internal fun helmetShouldConnect(
    device: AclDevice,
    helmetAddress: String?,
    namePrefix: String,
    helmetState: HelmetConnectionState,
    dispatchAllowed: Boolean,
    remoteConnected: Boolean,
): Boolean = isHelmetDevice(device, helmetAddress, namePrefix) &&
    dispatchAllowed &&
    remoteConnected &&
    helmetState != HelmetConnectionState.Connected &&
    helmetState != HelmetConnectionState.Connecting
