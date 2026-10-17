package de.werklade.cratoniremote.protocol

/**
 * Status frame the remote answers a READ on FFF4 with while no button was pressed.
 *
 * Measured empirically (50 samples over two weeks, `files/fff4-probe.log` of the debug build):
 * three bytes `[battery] [voltage high] [voltage low]`, the voltage as unsigned 16 bit big endian in millivolt.
 * `5A 0F FE` = 90/4094, `50 0F 42` = 80/3906, `46 0E ED` = 70/3821, `3C 0E 98` = 60/3736.
 *
 * UNVERIFIED: whether [batteryPercent] really means "percent" or only a coarse step. Byte 0 was never anything
 * but a multiple of ten (90, 80, 70, 60), monotonically falling, matching the discharge curve of a single
 * lithium cell (4101 → 3727 mV); the manufacturer app shows an icon instead of a number, so there is nothing to
 * compare against. The reported value is therefore passed on unchanged and never scaled or interpolated.
 */
data class RemoteStatusFrame(val batteryPercent: Int, val millivolts: Int)

/**
 * Parses the [RemoteStatusFrame] of a FFF4 read. Pure Kotlin, no Android dependencies.
 *
 * Deliberately separate from [CratoniProtocolParser]: that one parses the button packets of the FFF4
 * *notifications* (4 or 5 bytes, header `0x0F`, checksum), this one the answer of a FFF4 *read*. The same read
 * returns a button packet instead when a button was pressed a moment before, which [parseStatusFrame] rejects
 * by length — a 3 byte button packet does not exist.
 */
object RemoteStatusFrameParser {

    /** Exactly three bytes; anything else is not a status frame (button packets have 4 or 5). */
    const val LENGTH = 3

    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 100

    /**
     * Plausible voltage range of the single lithium cell in millivolt.
     *
     * Lower bound: a lithium cell is empty at 3.0 V and its data-sheet discharge cut-off is around 2.5 V, below
     * which no remote would still be transmitting. Upper bound: 4.2 V full charge plus headroom for the 4.35 V
     * high-voltage variant and for measurement tolerance. The measured range (3727 to 4101 mV) sits well inside,
     * while the 16 bit values of misread frames (e.g. `0x5503` = 21763) fall far outside.
     */
    const val MIN_MILLIVOLTS = 2_500
    const val MAX_MILLIVOLTS = 4_500

    /**
     * The status frame in [bytes], or `null` if [bytes] is not exactly [LENGTH] bytes, byte 0 is outside
     * [MIN_PERCENT]..[MAX_PERCENT] or the voltage is outside [MIN_MILLIVOLTS]..[MAX_MILLIVOLTS].
     *
     * Does not modify [bytes] and keeps no reference to it.
     */
    fun parseStatusFrame(bytes: ByteArray): RemoteStatusFrame? {
        if (bytes.size != LENGTH) return null
        val percent = unsigned(bytes[0])
        if (percent !in MIN_PERCENT..MAX_PERCENT) return null
        val millivolts = (unsigned(bytes[1]) shl 8) or unsigned(bytes[2])
        if (millivolts !in MIN_MILLIVOLTS..MAX_MILLIVOLTS) return null
        return RemoteStatusFrame(batteryPercent = percent, millivolts = millivolts)
    }

    private fun unsigned(byte: Byte): Int = byte.toInt() and 0xFF
}
