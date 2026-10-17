package de.werklade.cratoniremote.protocol

/**
 * Parses notification packets of the CRATONI SMARTRIDE 1.2 remote.
 *
 * The protocol is undocumented; it was measured empirically with nRF Connect
 * (see docs/anforderungen.md). Rules, checked in this order:
 * - length 0 → [InvalidReason.EMPTY], 1–3 → [InvalidReason.TOO_SHORT], > 5 → [InvalidReason.UNEXPECTED_LENGTH]
 * - first byte must be `0x0F` → otherwise [InvalidReason.INVALID_HEADER]
 * - last byte must equal `(unsigned sum of all preceding bytes + 1) and 0xFF` → otherwise [InvalidReason.INVALID_CHECKSUM]
 *
 * Valid packets are mapped by their exact, complete byte sequence; single bytes (e.g. `0x08`
 * in the turn signal packets) are deliberately not interpreted.
 *
 * [parse] does not modify its input and keeps no reference to it. It never throws for any packet
 * length BLE can realistically deliver (ATT values are at most 512 bytes); for extremely large
 * arrays, copying the input or building the hex representation may fail due to memory or
 * maximum array size.
 */
object CratoniProtocolParser {

    private const val HEADER = 0x0F
    private const val MIN_LENGTH = 4
    private const val MAX_LENGTH = 5
    private const val HEX_DIGITS = "0123456789ABCDEF"

    private val knownPackets: List<Pair<ByteArray, RemoteCommand>> = listOf(
        byteArrayOf(0x0F, 0x55, 0x03, 0x68) to RemoteCommand.PLAY_PAUSE,
        byteArrayOf(0x0F, 0x53, 0x02, 0x65) to RemoteCommand.NEXT,
        byteArrayOf(0x0F, 0x53, 0x01, 0x64) to RemoteCommand.PREVIOUS,
        byteArrayOf(0x0F, 0x54, 0x02, 0x66) to RemoteCommand.VOLUME_UP,
        byteArrayOf(0x0F, 0x54, 0x01, 0x65) to RemoteCommand.VOLUME_DOWN,
        byteArrayOf(0x0F, 0x56, 0x03, 0x69) to RemoteCommand.CALL,
        byteArrayOf(0x0F, 0x57, 0x03, 0x6A) to RemoteCommand.WALKIE_TALKIE,
        byteArrayOf(0x0F, 0x50, 0x01, 0x08, 0x69) to RemoteCommand.TURN_LEFT,
        byteArrayOf(0x0F, 0x50, 0x02, 0x08, 0x6A) to RemoteCommand.TURN_RIGHT,
        byteArrayOf(0x0F, 0x59, 0x01, 0x6A) to RemoteCommand.REAR_LIGHT,
    )

    fun parse(packet: ByteArray): ParseResult {
        // Snapshot so validation, mapping and hex output see the same bytes.
        val bytes = packet.copyOf()
        val hex = toHex(bytes)

        val reason = when {
            bytes.isEmpty() -> InvalidReason.EMPTY
            bytes.size < MIN_LENGTH -> InvalidReason.TOO_SHORT
            bytes.size > MAX_LENGTH -> InvalidReason.UNEXPECTED_LENGTH
            unsigned(bytes[0]) != HEADER -> InvalidReason.INVALID_HEADER
            unsigned(bytes[bytes.lastIndex]) != checksum(bytes, bytes.size - 1) -> InvalidReason.INVALID_CHECKSUM
            else -> null
        }
        if (reason != null) return ParseResult.Invalid(reason, hex)

        val command = knownPackets.firstOrNull { (known, _) -> known.contentEquals(bytes) }?.second
            ?: RemoteCommand.UNKNOWN
        return ParseResult.Command(command, hex)
    }

    /**
     * `(unsigned sum of bytes[0 until count] + 1) and 0xFF`, in `0..255`.
     * [count] must be in `0..bytes.size`.
     */
    internal fun checksum(bytes: ByteArray, count: Int): Int {
        var sum = 1
        for (i in 0 until count) {
            sum += unsigned(bytes[i])
        }
        return sum and 0xFF
    }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 3) {
        bytes.forEachIndexed { index, byte ->
            if (index > 0) append(' ')
            val value = unsigned(byte)
            append(HEX_DIGITS[value shr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }

    private fun unsigned(byte: Byte): Int = byte.toInt() and 0xFF
}
