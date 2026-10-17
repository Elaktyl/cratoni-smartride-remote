package de.werklade.cratoniremote.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleHexTest {

    @Test
    fun `empty array is an empty string`() {
        assertEquals("", BleHex.format(byteArrayOf()))
    }

    @Test
    fun `single byte has no separator`() {
        assertEquals("64", BleHex.format(byteArrayOf(0x64)))
    }

    @Test
    fun `bytes are upper case, zero padded and separated by single spaces`() {
        assertEquals("0F 55 03 68", BleHex.format(byteArrayOf(0x0F, 0x55, 0x03, 0x68)))
    }

    @Test
    fun `bytes are formatted unsigned`() {
        assertEquals("00 7F 80 FF", BleHex.format(byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())))
    }
}
