package de.werklade.cratoniremote.ble

/** Receives FFF4 notification packets from [CratoniBleManager]. */
fun interface PacketListener {

    /**
     * Called on the manager's BLE thread, once per notification and in reception order.
     *
     * [packet] is a private copy of the notified value; the receiver may keep or modify it.
     * Must return quickly: while it runs, no further GATT callbacks are processed.
     * An [Exception] thrown here is caught and logged by the manager.
     */
    fun onPacket(packet: ByteArray)
}
