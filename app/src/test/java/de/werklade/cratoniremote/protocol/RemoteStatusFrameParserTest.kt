package de.werklade.cratoniremote.protocol

import de.werklade.cratoniremote.protocol.RemoteStatusFrameParser.parseStatusFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteStatusFrameParserTest {

    // The four measured examples (files/fff4-probe.log of the debug build)

    @Test
    fun `5A 0F FE is 90 percent and 4094 millivolts`() {
        assertEquals(RemoteStatusFrame(90, 4_094), parseStatusFrame(bytes(0x5A, 0x0F, 0xFE)))
    }

    @Test
    fun `50 0F 42 is 80 percent and 3906 millivolts`() {
        assertEquals(RemoteStatusFrame(80, 3_906), parseStatusFrame(bytes(0x50, 0x0F, 0x42)))
    }

    @Test
    fun `46 0E ED is 70 percent and 3821 millivolts`() {
        assertEquals(RemoteStatusFrame(70, 3_821), parseStatusFrame(bytes(0x46, 0x0E, 0xED)))
    }

    @Test
    fun `3C 0E 98 is 60 percent and 3736 millivolts`() {
        assertEquals(RemoteStatusFrame(60, 3_736), parseStatusFrame(bytes(0x3C, 0x0E, 0x98)))
    }

    // Accepted boundaries

    @Test
    fun `percentage 0 is accepted`() {
        assertEquals(RemoteStatusFrame(0, 3_736), parseStatusFrame(bytes(0x00, 0x0E, 0x98)))
    }

    @Test
    fun `percentage 100 is accepted`() {
        assertEquals(RemoteStatusFrame(100, 4_094), parseStatusFrame(bytes(0x64, 0x0F, 0xFE)))
    }

    @Test
    fun `the lowest plausible voltage is accepted`() {
        val frame = parseStatusFrame(bytes(0x0A, 0x09, 0xC4))
        assertEquals(RemoteStatusFrameParser.MIN_MILLIVOLTS, frame?.millivolts)
    }

    @Test
    fun `the highest plausible voltage is accepted`() {
        val frame = parseStatusFrame(bytes(0x64, 0x11, 0x94))
        assertEquals(RemoteStatusFrameParser.MAX_MILLIVOLTS, frame?.millivolts)
    }

    /**
     * A status frame may well start with `0x0F` — that is 15 %. It cannot be confused with a button packet,
     * which never has three bytes.
     */
    @Test
    fun `a status frame starting with 0F is read as 15 percent`() {
        assertEquals(RemoteStatusFrame(15, 3_736), parseStatusFrame(bytes(0x0F, 0x0E, 0x98)))
    }

    // Rejected

    @Test
    fun `percentage 101 is rejected`() {
        assertNull(parseStatusFrame(bytes(0x65, 0x0E, 0x98)))
    }

    @Test
    fun `percentage 0xFF is rejected and not read as -1`() {
        assertNull(parseStatusFrame(bytes(0xFF, 0x0E, 0x98)))
    }

    @Test
    fun `a voltage one millivolt below the plausible range is rejected`() {
        assertNull(parseStatusFrame(bytes(0x3C, 0x09, 0xC3)))
    }

    @Test
    fun `a voltage one millivolt above the plausible range is rejected`() {
        assertNull(parseStatusFrame(bytes(0x3C, 0x11, 0x95)))
    }

    @Test
    fun `a voltage of zero is rejected`() {
        assertNull(parseStatusFrame(bytes(0x3C, 0x00, 0x00)))
    }

    @Test
    fun `the largest possible voltage value is rejected`() {
        assertNull(parseStatusFrame(bytes(0x3C, 0xFF, 0xFF)))
    }

    @Test
    fun `an empty value is rejected`() {
        assertNull(parseStatusFrame(byteArrayOf()))
    }

    @Test
    fun `two bytes are rejected`() {
        assertNull(parseStatusFrame(bytes(0x3C, 0x0E)))
    }

    /** The same read returns this right after a button press; it must not be taken for a measurement. */
    @Test
    fun `a four byte button packet is rejected`() {
        assertNull(parseStatusFrame(bytes(0x0F, 0x55, 0x03, 0x68)))
    }

    @Test
    fun `a five byte button packet is rejected`() {
        assertNull(parseStatusFrame(bytes(0x0F, 0x50, 0x01, 0x08, 0x69)))
    }

    @Test
    fun `parse does not modify the value`() {
        val value = bytes(0x3C, 0x0E, 0x98)
        parseStatusFrame(value)
        assertArrayEquals(bytes(0x3C, 0x0E, 0x98), value)
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
