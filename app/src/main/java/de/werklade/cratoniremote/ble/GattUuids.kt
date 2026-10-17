package de.werklade.cratoniremote.ble

import java.util.UUID

/**
 * GATT UUIDs of the CRATONI SMARTRIDE 1.2 remote used by the app (docs/architektur.md §5).
 *
 * The WRITE-NO-RESPONSE characteristics `…2b11` and `…2b12` are deliberately not listed:
 * the app never writes to them.
 */
object GattUuids {

    /** Proprietary service 0xFFF0; contains [CHARACTERISTIC_FFF4] and [CHARACTERISTIC_BATTERY_LEVEL]. */
    val SERVICE_FFF0: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")

    /** Button packets 0xFFF4 (READ, NOTIFY). */
    val CHARACTERISTIC_FFF4: UUID = UUID.fromString("0000FFF4-0000-1000-8000-00805F9B34FB")

    /** Client Characteristic Configuration Descriptor 0x2902. */
    val DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** Battery Level 0x2A19; on this remote located in [SERVICE_FFF0], NOTIFY only (no READ). */
    val CHARACTERISTIC_BATTERY_LEVEL: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

    /** Device Information service 0x180A. */
    val SERVICE_DEVICE_INFORMATION: UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")

    /** PnP ID 0x2A50 in [SERVICE_DEVICE_INFORMATION] (READ). */
    val CHARACTERISTIC_PNP_ID: UUID = UUID.fromString("00002A50-0000-1000-8000-00805F9B34FB")
}
