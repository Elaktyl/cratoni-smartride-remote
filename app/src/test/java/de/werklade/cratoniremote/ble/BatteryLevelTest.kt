package de.werklade.cratoniremote.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryLevelTest {

    // Accepted

    @Test
    fun `single byte 0 is 0 percent`() {
        assertEquals(0, BatteryLevel.parse(byteArrayOf(0)))
    }

    @Test
    fun `single byte 57 is 57 percent`() {
        assertEquals(57, BatteryLevel.parse(byteArrayOf(0x39)))
    }

    @Test
    fun `single byte 100 is 100 percent`() {
        assertEquals(100, BatteryLevel.parse(byteArrayOf(0x64)))
    }

    // Rejected

    @Test
    fun `single byte 101 is rejected`() {
        assertNull(BatteryLevel.parse(byteArrayOf(0x65)))
    }

    @Test
    fun `single byte 0x80 is rejected`() {
        assertNull(BatteryLevel.parse(byteArrayOf(0x80.toByte())))
    }

    @Test
    fun `single byte 0xFF is rejected and not read as -1`() {
        assertNull(BatteryLevel.parse(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `empty value is rejected`() {
        assertNull(BatteryLevel.parse(byteArrayOf()))
    }

    @Test
    fun `two bytes are rejected even if the first byte is valid`() {
        assertNull(BatteryLevel.parse(byteArrayOf(50, 0)))
    }

    @Test
    fun `parse does not modify the value`() {
        val value = byteArrayOf(0x39)
        BatteryLevel.parse(value)
        assertArrayEquals(byteArrayOf(0x39), value)
    }
}
