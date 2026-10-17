package de.werklade.cratoniremote.ble

/**
 * Validation of Battery Level 0x2A19 notification values (docs/architektur.md §2). Pure Kotlin.
 *
 * The value format of this remote is not verified yet (assumed: uint8 percent), so only exactly
 * one byte with an unsigned value in [MIN_PERCENT]..[MAX_PERCENT] is accepted.
 */
object BatteryLevel {

    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 100

    /** Battery percentage, or `null` if [value] is not exactly one byte in `0..100`. Does not modify [value]. */
    fun parse(value: ByteArray): Int? {
        if (value.size != 1) return null
        val percent = value[0].toInt() and 0xFF
        return if (percent in MIN_PERCENT..MAX_PERCENT) percent else null
    }
}
