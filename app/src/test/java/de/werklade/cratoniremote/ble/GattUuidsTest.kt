package de.werklade.cratoniremote.ble

import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the UUID string constants against typos: each must be the 16-bit short form on the Bluetooth base UUID. */
class GattUuidsTest {

    private fun shortForm(value: Int): UUID =
        UUID.fromString(String.format(Locale.ROOT, "0000%04X-0000-1000-8000-00805F9B34FB", value))

    @Test
    fun `service FFF0`() = assertEquals(shortForm(0xFFF0), GattUuids.SERVICE_FFF0)

    @Test
    fun `characteristic FFF4`() = assertEquals(shortForm(0xFFF4), GattUuids.CHARACTERISTIC_FFF4)

    @Test
    fun `descriptor CCCD 2902`() = assertEquals(shortForm(0x2902), GattUuids.DESCRIPTOR_CCCD)

    @Test
    fun `characteristic Battery Level 2A19`() = assertEquals(shortForm(0x2A19), GattUuids.CHARACTERISTIC_BATTERY_LEVEL)

    @Test
    fun `service Device Information 180A`() = assertEquals(shortForm(0x180A), GattUuids.SERVICE_DEVICE_INFORMATION)

    @Test
    fun `characteristic PnP ID 2A50`() = assertEquals(shortForm(0x2A50), GattUuids.CHARACTERISTIC_PNP_ID)
}
