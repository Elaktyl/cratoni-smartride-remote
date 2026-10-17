package de.werklade.cratoniremote.ble

import java.util.Locale

/**
 * Redaction for BLE logs. Pure Kotlin.
 *
 * A Bluetooth MAC address identifies a device (and with it its owner) across apps and reboots, so it never goes
 * into the log in full — not in release and not in debug, so that a log captured during a device test can be
 * shared without stripping it first. The last two bytes are kept: they are enough to tell two devices apart
 * while reading a log and they match the suffix of the advertised name (`"Remote control-3D2C"`).
 */
internal object BleLog {

    private const val MASK = "xx:xx:xx:xx"

    /**
     * [address] with everything but its last two bytes masked, e.g. `"A4:C1:38:1B:2C:3D"` → `"xx:xx:xx:xx:2C:3D"`.
     *
     * `"<none>"` for `null`, `"<redacted>"` for anything that is not a six byte colon-separated address (never
     * echo unparsed input).
     */
    fun mac(address: String?): String {
        if (address == null) return "<none>"
        val parts = address.split(':')
        if (parts.size != 6 || parts.any { part -> part.length != 2 || !part.all { it.isHexDigit() } }) {
            return "<redacted>"
        }
        return "$MASK:${parts[4].uppercase(Locale.ROOT)}:${parts[5].uppercase(Locale.ROOT)}"
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
