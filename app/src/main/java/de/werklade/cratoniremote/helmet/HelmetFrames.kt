package de.werklade.cratoniremote.helmet

/**
 * The frames of the helmet's proprietary SPP protocol (docs/architektur.md §13). Pure Kotlin.
 *
 * Format: `0F [command] [target] [parameter] [checksum]`, where the keep-alive frame has no parameter byte.
 * The checksum is the **unsigned sum of all preceding bytes and 0xFF, without the `+ 1`** of the remote
 * protocol of the same product family (`de.werklade.cratoniremote.protocol.CratoniProtocolParser`) — the two
 * rules differ, which is why they are computed in two places instead of shared.
 *
 * Only the byte sequences that were measured on the manufacturer app are built here; there is no general
 * "set any light to any value" entry point, so a value outside the measured table cannot be sent by accident:
 * - turn signals: parameter `0x08` on, `0x00` off, targets `0x01` left and `0x02` right
 * - rear light: parameter `0xFF` solid, `0xFE` blinking, `0x00` off, target `0x04`
 *
 * The helmet does not acknowledge light frames, so nothing here parses a response.
 */
object HelmetFrames {

    /** First byte of every frame. */
    const val HEADER = 0x0F

    /** Command byte "set light". */
    private const val COMMAND_LIGHT = 0x01

    /** Command byte of the keep-alive frame. */
    private const val COMMAND_KEEP_ALIVE = 0x05

    private const val TARGET_LEFT = 0x01
    private const val TARGET_RIGHT = 0x02
    private const val TARGET_REAR = 0x04
    private const val TARGET_NONE = 0x00

    private const val TURN_SIGNAL_ON = 0x08
    private const val OFF = 0x00
    private const val REAR_SOLID = 0xFF
    private const val REAR_BLINKING = 0xFE

    /**
     * `0F 05 00 14`, which the manufacturer app sends about every ten seconds.
     *
     * Deliberately not sent by this app (see [HelmetSppClient]); it is kept because it is measured and because
     * it is the second independent proof of the checksum rule: `0x0F + 0x05 + 0x00 = 0x14`.
     */
    val KEEP_ALIVE: ByteArray = frame(COMMAND_KEEP_ALIVE, TARGET_NONE)

    /** `0F 01 01 08 19` / `0F 01 01 00 11` for the left, `0F 01 02 08 1A` / `0F 01 02 00 12` for the right side. */
    fun turnSignal(side: TurnSignal, on: Boolean): ByteArray = frame(
        COMMAND_LIGHT,
        when (side) {
            TurnSignal.LEFT -> TARGET_LEFT
            TurnSignal.RIGHT -> TARGET_RIGHT
        },
        if (on) TURN_SIGNAL_ON else OFF,
    )

    /** `0F 01 04 FF 13` solid, `0F 01 04 FE 12` blinking, `0F 01 04 00 14` off. */
    fun rearLight(state: RearLight): ByteArray = frame(
        COMMAND_LIGHT,
        TARGET_REAR,
        when (state) {
            RearLight.OFF -> OFF
            RearLight.SOLID -> REAR_SOLID
            RearLight.BLINKING -> REAR_BLINKING
        },
    )

    /** [HEADER], the given bytes and the checksum over all of them. */
    private fun frame(vararg bytes: Int): ByteArray {
        val frame = ByteArray(bytes.size + 2)
        frame[0] = HEADER.toByte()
        bytes.forEachIndexed { index, byte -> frame[index + 1] = byte.toByte() }
        frame[frame.lastIndex] = checksum(frame, frame.size - 1).toByte()
        return frame
    }

    /**
     * `unsigned sum of bytes[0 until count] and 0xFF`, in `0..255` — **without** the `+ 1` of the remote
     * protocol. [count] must be in `0..bytes.size`.
     */
    internal fun checksum(bytes: ByteArray, count: Int): Int {
        var sum = 0
        for (i in 0 until count) {
            sum += bytes[i].toInt() and 0xFF
        }
        return sum and 0xFF
    }
}
