package de.werklade.cratoniremote.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BleLogTest {

    @Test
    fun `only the last two bytes of a MAC address are logged`() {
        assertEquals("xx:xx:xx:xx:2C:3D", BleLog.mac("A4:C1:38:1B:2C:3D"))
    }

    @Test
    fun `the kept bytes are logged in upper case`() {
        assertEquals("xx:xx:xx:xx:2C:3D", BleLog.mac("a4:c1:38:1b:2c:3d"))
    }

    @Test
    fun `no part of the first four bytes survives`() {
        val masked = BleLog.mac("A4:C1:38:1B:2C:3D")
        listOf("A4", "C1", "38", "1B").forEach {
            assertFalse("'$it' must not appear in '$masked'", masked.contains(it))
        }
    }

    @Test
    fun `null address is logged as none`() {
        assertEquals("<none>", BleLog.mac(null))
    }

    @Test
    fun `input that is not a MAC address is never echoed`() {
        listOf("", "A4:C1:38:1B:2C", "A4:C1:38:1B:2C:3D:B3", "A4C1381B 2C3D", "A4:C1:38:1B:2C:ZZ", "hello")
            .forEach { assertEquals("input '$it'", "<redacted>", BleLog.mac(it)) }
    }
}
