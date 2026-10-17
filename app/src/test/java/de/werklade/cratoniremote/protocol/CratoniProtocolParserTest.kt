package de.werklade.cratoniremote.protocol

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CratoniProtocolParserTest {

    // Known packets

    @Test
    fun `0F 55 03 68 maps to PLAY_PAUSE`() {
        assertEquals(ParseResult.Command(RemoteCommand.PLAY_PAUSE, "0F 55 03 68"), parse(0x0F, 0x55, 0x03, 0x68))
    }

    @Test
    fun `0F 53 02 65 maps to NEXT`() {
        assertEquals(ParseResult.Command(RemoteCommand.NEXT, "0F 53 02 65"), parse(0x0F, 0x53, 0x02, 0x65))
    }

    @Test
    fun `0F 53 01 64 maps to PREVIOUS`() {
        assertEquals(ParseResult.Command(RemoteCommand.PREVIOUS, "0F 53 01 64"), parse(0x0F, 0x53, 0x01, 0x64))
    }

    @Test
    fun `0F 54 02 66 maps to VOLUME_UP`() {
        assertEquals(ParseResult.Command(RemoteCommand.VOLUME_UP, "0F 54 02 66"), parse(0x0F, 0x54, 0x02, 0x66))
    }

    @Test
    fun `0F 54 01 65 maps to VOLUME_DOWN`() {
        assertEquals(ParseResult.Command(RemoteCommand.VOLUME_DOWN, "0F 54 01 65"), parse(0x0F, 0x54, 0x01, 0x65))
    }

    @Test
    fun `0F 56 03 69 maps to CALL`() {
        assertEquals(ParseResult.Command(RemoteCommand.CALL, "0F 56 03 69"), parse(0x0F, 0x56, 0x03, 0x69))
    }

    @Test
    fun `0F 57 03 6A maps to WALKIE_TALKIE`() {
        assertEquals(ParseResult.Command(RemoteCommand.WALKIE_TALKIE, "0F 57 03 6A"), parse(0x0F, 0x57, 0x03, 0x6A))
    }

    @Test
    fun `0F 50 01 08 69 maps to TURN_LEFT`() {
        assertEquals(
            ParseResult.Command(RemoteCommand.TURN_LEFT, "0F 50 01 08 69"),
            parse(0x0F, 0x50, 0x01, 0x08, 0x69),
        )
    }

    @Test
    fun `0F 50 02 08 6A maps to TURN_RIGHT`() {
        assertEquals(
            ParseResult.Command(RemoteCommand.TURN_RIGHT, "0F 50 02 08 6A"),
            parse(0x0F, 0x50, 0x02, 0x08, 0x6A),
        )
    }

    @Test
    fun `0F 59 01 6A maps to REAR_LIGHT`() {
        assertEquals(ParseResult.Command(RemoteCommand.REAR_LIGHT, "0F 59 01 6A"), parse(0x0F, 0x59, 0x01, 0x6A))
    }

    // Formally valid, unknown packets

    @Test
    fun `unknown 4-byte packet with valid checksum is UNKNOWN`() {
        // 0F + 58 + 03 + 1 = 6B
        assertEquals(ParseResult.Command(RemoteCommand.UNKNOWN, "0F 58 03 6B"), parse(0x0F, 0x58, 0x03, 0x6B))
    }

    @Test
    fun `unknown 5-byte packet with valid checksum is UNKNOWN`() {
        // 0F + 50 + 03 + 08 + 1 = 6B
        assertEquals(
            ParseResult.Command(RemoteCommand.UNKNOWN, "0F 50 03 08 6B"),
            parse(0x0F, 0x50, 0x03, 0x08, 0x6B),
        )
    }

    // Mapping by exact byte sequence only, 0x08 is not interpreted

    @Test
    fun `turn signal prefix with 09 instead of 08 is UNKNOWN`() {
        // 0F + 50 + 01 + 09 + 1 = 6A
        assertEquals(
            ParseResult.Command(RemoteCommand.UNKNOWN, "0F 50 01 09 6A"),
            parse(0x0F, 0x50, 0x01, 0x09, 0x6A),
        )
    }

    @Test
    fun `turn signal prefix without 08 byte is UNKNOWN`() {
        // 0F + 50 + 01 + 1 = 61
        assertEquals(ParseResult.Command(RemoteCommand.UNKNOWN, "0F 50 01 61"), parse(0x0F, 0x50, 0x01, 0x61))
    }

    @Test
    fun `PLAY_PAUSE prefix with appended 08 byte is UNKNOWN`() {
        // 0F + 55 + 03 + 08 + 1 = 70
        assertEquals(
            ParseResult.Command(RemoteCommand.UNKNOWN, "0F 55 03 08 70"),
            parse(0x0F, 0x55, 0x03, 0x08, 0x70),
        )
    }

    // Invalid packets

    @Test
    fun `empty packet is EMPTY`() {
        assertEquals(ParseResult.Invalid(InvalidReason.EMPTY, ""), parse())
    }

    @Test
    fun `1-byte packet is TOO_SHORT`() {
        assertEquals(ParseResult.Invalid(InvalidReason.TOO_SHORT, "0F"), parse(0x0F))
    }

    @Test
    fun `3-byte packet is TOO_SHORT`() {
        assertEquals(ParseResult.Invalid(InvalidReason.TOO_SHORT, "0F 55 03"), parse(0x0F, 0x55, 0x03))
    }

    @Test
    fun `6-byte packet is UNEXPECTED_LENGTH even with valid header and checksum`() {
        // 0F + 50 + 01 + 08 + 69 + 1 = D2
        assertEquals(
            ParseResult.Invalid(InvalidReason.UNEXPECTED_LENGTH, "0F 50 01 08 69 D2"),
            parse(0x0F, 0x50, 0x01, 0x08, 0x69, 0xD2),
        )
    }

    @Test
    fun `wrong header with otherwise valid checksum is INVALID_HEADER`() {
        // 0E + 55 + 03 + 1 = 67
        assertEquals(ParseResult.Invalid(InvalidReason.INVALID_HEADER, "0E 55 03 67"), parse(0x0E, 0x55, 0x03, 0x67))
    }

    @Test
    fun `header is checked before checksum`() {
        // 0E + 55 + 03 + 1 = 67, so 68 is also a wrong checksum
        assertEquals(ParseResult.Invalid(InvalidReason.INVALID_HEADER, "0E 55 03 68"), parse(0x0E, 0x55, 0x03, 0x68))
    }

    @Test
    fun `wrong checksum is INVALID_CHECKSUM`() {
        assertEquals(
            ParseResult.Invalid(InvalidReason.INVALID_CHECKSUM, "0F 55 03 69"),
            parse(0x0F, 0x55, 0x03, 0x69),
        )
    }

    // Signed bytes and overflow

    @Test
    fun `bytes above 0x7F are summed unsigned and wrap around`() {
        // 0F + FF + FF = 20D, + 1 = 20E -> 0E
        assertEquals(ParseResult.Command(RemoteCommand.UNKNOWN, "0F FF FF 0E"), parse(0x0F, 0xFF, 0xFF, 0x0E))
    }

    @Test
    fun `bytes above 0x7F with wrong checksum are INVALID_CHECKSUM`() {
        assertEquals(
            ParseResult.Invalid(InvalidReason.INVALID_CHECKSUM, "0F FF FF 0D"),
            parse(0x0F, 0xFF, 0xFF, 0x0D),
        )
    }

    @Test
    fun `checksum byte above 0x7F is compared unsigned`() {
        // 0F + 70 + 00 + 1 = 80
        assertEquals(ParseResult.Command(RemoteCommand.UNKNOWN, "0F 70 00 80"), parse(0x0F, 0x70, 0x00, 0x80))
    }

    // Checksum function

    @Test
    fun `checksum matches all known packets`() {
        val payloadsToChecksum = listOf(
            bytes(0x0F, 0x55, 0x03) to 0x68,
            bytes(0x0F, 0x53, 0x02) to 0x65,
            bytes(0x0F, 0x53, 0x01) to 0x64,
            bytes(0x0F, 0x54, 0x02) to 0x66,
            bytes(0x0F, 0x54, 0x01) to 0x65,
            bytes(0x0F, 0x56, 0x03) to 0x69,
            bytes(0x0F, 0x57, 0x03) to 0x6A,
            bytes(0x0F, 0x50, 0x01, 0x08) to 0x69,
            bytes(0x0F, 0x50, 0x02, 0x08) to 0x6A,
            bytes(0x0F, 0x59, 0x01) to 0x6A,
        )
        for ((payload, expected) in payloadsToChecksum) {
            assertEquals(payload.contentToString(), expected, CratoniProtocolParser.checksum(payload, payload.size))
        }
    }

    @Test
    fun `checksum with count ignores trailing checksum byte`() {
        assertEquals(0x68, CratoniProtocolParser.checksum(bytes(0x0F, 0x55, 0x03, 0x68), 3))
    }

    @Test
    fun `checksum wraps unsigned sum`() {
        assertEquals(0x0E, CratoniProtocolParser.checksum(bytes(0x0F, 0xFF, 0xFF), 3))
    }

    // Input handling and representation

    @Test
    fun `parse does not modify input`() {
        val packets = listOf(
            bytes(),
            bytes(0x0F, 0x55, 0x03),
            bytes(0x0F, 0x55, 0x03, 0x68),
            bytes(0x0F, 0x55, 0x03, 0x69),
            bytes(0x0F, 0xFF, 0xFF, 0x0E),
            bytes(0x0F, 0x50, 0x01, 0x08, 0x69),
            bytes(0x0F, 0x50, 0x01, 0x08, 0x69, 0xD2),
        )
        for (packet in packets) {
            val snapshot = packet.copyOf()
            CratoniProtocolParser.parse(packet)
            assertArrayEquals(snapshot, packet)
        }
    }

    @Test
    fun `result is not affected by later changes to input`() {
        val packet = bytes(0x0F, 0x55, 0x03, 0x68)
        val result = CratoniProtocolParser.parse(packet)
        packet.fill(0)
        assertEquals(ParseResult.Command(RemoteCommand.PLAY_PAUSE, "0F 55 03 68"), result)
    }

    @Test
    fun `rawHex is uppercase and space separated`() {
        assertEquals("0F 50 01 08 69", parse(0x0F, 0x50, 0x01, 0x08, 0x69).rawHex)
        assertEquals("0F FF FF 0D", parse(0x0F, 0xFF, 0xFF, 0x0D).rawHex)
        assertEquals("00 AB CD EF 0A", parse(0x00, 0xAB, 0xCD, 0xEF, 0x0A).rawHex)
    }

    @Test
    fun `results of equal packets from different arrays are equal`() {
        val first = parse(0x0F, 0x55, 0x03, 0x68)
        val second = parse(0x0F, 0x55, 0x03, 0x68)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `parse never throws on random input`() {
        val random = Random(42)
        var validCount = 0
        var invalidCount = 0
        repeat(10_000) {
            val packet = random.nextBytes(random.nextInt(0, 9))
            when (random.nextInt(3)) {
                0 -> Unit // fully random
                1 -> if (packet.isNotEmpty()) packet[0] = 0x0F
                else -> if (packet.size >= 2) {
                    // Deliberately well-formed, so formally valid packets actually occur.
                    packet[0] = 0x0F
                    packet[packet.lastIndex] = oracleChecksum(packet).toByte()
                }
            }
            val result = CratoniProtocolParser.parse(packet)
            assertEquals(if (packet.isEmpty()) 0 else packet.size * 3 - 1, result.rawHex.length)

            val valid = isFormallyValid(packet)
            assertEquals(result.rawHex, valid, result is ParseResult.Command)
            if (valid) validCount++ else invalidCount++
        }
        assertTrue("no valid packets generated", validCount > 0)
        assertTrue("no invalid packets generated", invalidCount > 0)
    }

    /** Test oracle, independent of [CratoniProtocolParser.checksum]. */
    private fun isFormallyValid(packet: ByteArray): Boolean =
        (packet.size == 4 || packet.size == 5) &&
            (packet[0].toInt() and 0xFF) == 0x0F &&
            (packet[packet.lastIndex].toInt() and 0xFF) == oracleChecksum(packet)

    /** `(unsigned sum of all bytes except the last + 1) and 0xFF`. */
    private fun oracleChecksum(packet: ByteArray): Int {
        var sum = 0
        for (i in 0 until packet.size - 1) sum += packet[i].toInt() and 0xFF
        return (sum + 1) and 0xFF
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun parse(vararg values: Int): ParseResult = CratoniProtocolParser.parse(bytes(*values))
}
