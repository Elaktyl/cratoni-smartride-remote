package de.werklade.cratoniremote.ble

/** Hex formatting for BLE logs, in the same format as `ParseResult.rawHex`. Pure Kotlin. */
internal object BleHex {

    private const val DIGITS = "0123456789ABCDEF"

    /** Uppercase hex bytes separated by single spaces, e.g. `"0F 55 03 68"`; empty string for an empty array. */
    fun format(bytes: ByteArray): String = buildString(bytes.size * 3) {
        bytes.forEachIndexed { index, byte ->
            if (index > 0) append(' ')
            val value = byte.toInt() and 0xFF
            append(DIGITS[value shr 4])
            append(DIGITS[value and 0x0F])
        }
    }
}
