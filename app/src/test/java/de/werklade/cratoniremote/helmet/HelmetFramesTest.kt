package de.werklade.cratoniremote.helmet

import de.werklade.cratoniremote.protocol.CratoniProtocolParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Every frame is compared byte for byte against the sequences measured on the manufacturer app
 * (docs/architektur.md §13). These bytes are the contract with the helmet: nothing else may ever be sent, and a
 * single wrong byte would either do nothing or switch the wrong light.
 */
class HelmetFramesTest {

    @Test
    fun `left turn signal on is 0F 01 01 08 19`() {
        assertArrayEquals(bytes(0x0F, 0x01, 0x01, 0x08, 0x19), HelmetFrames.turnSignal(TurnSignal.LEFT, on = true))
    }

    @Test
    fun `left turn signal off is 0F 01 01 00 11`() {
        assertArrayEquals(bytes(0x0F, 0x01, 0x01, 0x00, 0x11), HelmetFrames.turnSignal(TurnSignal.LEFT, on = false))
    }

    @Test
    fun `right turn signal on is 0F 01 02 08 1A`() {
        assertArrayEquals(bytes(0x0F, 0x01, 0x02, 0x08, 0x1A), HelmetFrames.turnSignal(TurnSignal.RIGHT, on = true))
    }

    @Test
    fun `right turn signal off is 0F 01 02 00 12`() {
        assertArrayEquals(bytes(0x0F, 0x01, 0x02, 0x00, 0x12), HelmetFrames.turnSignal(TurnSignal.RIGHT, on = false))
    }

    @Test
    fun `rear light solid is 0F 01 04 FF 13`() {
        assertArrayEquals(bytes(0x0F, 0x01, 0x04, 0xFF, 0x13), HelmetFrames.rearLight(RearLight.SOLID))
    }

    @Test
    fun `rear light blinking is 0F 01 04 FE 12`() {
        assertArrayEquals(bytes(0x0F, 0x01, 0x04, 0xFE, 0x12), HelmetFrames.rearLight(RearLight.BLINKING))
    }

    @Test
    fun `rear light off is 0F 01 04 00 14`() {
        assertArrayEquals(bytes(0x0F, 0x01, 0x04, 0x00, 0x14), HelmetFrames.rearLight(RearLight.OFF))
    }

    /** Four bytes, no parameter — and the second measured proof of the checksum rule. */
    @Test
    fun `keep alive is 0F 05 00 14`() {
        assertArrayEquals(bytes(0x0F, 0x05, 0x00, 0x14), HelmetFrames.KEEP_ALIVE)
    }

    @Test
    fun `every frame starts with the header`() {
        for (frame in allFrames()) {
            assertEquals(frame.contentToString(), HelmetFrames.HEADER.toByte(), frame[0])
        }
    }

    @Test
    fun `every frame ends with the sum of its preceding bytes`() {
        for (frame in allFrames()) {
            val expected = HelmetFrames.checksum(frame, frame.size - 1)
            assertEquals(frame.contentToString(), expected, frame[frame.lastIndex].toInt() and 0xFF)
        }
    }

    /** A returned array must not be a shared instance a caller could modify for everyone else. */
    @Test
    fun `frames are built fresh on every call`() {
        val first = HelmetFrames.rearLight(RearLight.SOLID)
        first.fill(0)

        assertArrayEquals(bytes(0x0F, 0x01, 0x04, 0xFF, 0x13), HelmetFrames.rearLight(RearLight.SOLID))
    }

    // Checksum function

    @Test
    fun `checksum has no plus one and wraps unsigned`() {
        assertEquals(0x19, HelmetFrames.checksum(bytes(0x0F, 0x01, 0x01, 0x08), 4))
        assertEquals(0x13, HelmetFrames.checksum(bytes(0x0F, 0x01, 0x04, 0xFF), 4))
        assertEquals(0x0D, HelmetFrames.checksum(bytes(0x0F, 0xFF, 0xFF), 3))
        assertEquals(0x00, HelmetFrames.checksum(bytes(), 0))
    }

    @Test
    fun `checksum with count ignores trailing bytes`() {
        assertEquals(0x19, HelmetFrames.checksum(bytes(0x0F, 0x01, 0x01, 0x08, 0x19), 4))
    }

    /**
     * The two protocols of the same product family differ by exactly one: the remote adds `+ 1`, the helmet does
     * not. Mixing them up would be invisible in a review and fatal in the field, so the difference is asserted.
     */
    @Test
    fun `helmet checksum is exactly one below the remote checksum of the same payload`() {
        val payloads = listOf(
            bytes(0x0F, 0x01, 0x01, 0x08),
            bytes(0x0F, 0x01, 0x04, 0xFF),
            bytes(0x0F, 0x05, 0x00),
        )
        for (payload in payloads) {
            val helmet = HelmetFrames.checksum(payload, payload.size)
            val remote = CratoniProtocolParser.checksum(payload, payload.size)
            assertNotEquals(payload.contentToString(), remote, helmet)
            assertEquals(payload.contentToString(), (helmet + 1) and 0xFF, remote)
        }
    }

    private fun allFrames(): List<ByteArray> = buildList {
        for (side in TurnSignal.entries) {
            add(HelmetFrames.turnSignal(side, on = true))
            add(HelmetFrames.turnSignal(side, on = false))
        }
        for (state in RearLight.entries) {
            add(HelmetFrames.rearLight(state))
        }
        add(HelmetFrames.KEEP_ALIVE)
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
