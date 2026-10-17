package de.werklade.cratoniremote.companion

import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.FailureReason
import de.werklade.cratoniremote.data.CONFIRMATION_TIMEOUT_MS
import de.werklade.cratoniremote.data.PendingConfirmation
import de.werklade.cratoniremote.data.RemoteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The rule behind [CratoniCompanionService]'s handling of `BLE_DISAPPEARED`. */
class PresenceKeepAliveTest {

    @Test
    fun `a running connection keeps the service alive`() {
        assertNotNull(keepAliveReason(RemoteState(serviceRunning = true, connection = BleConnectionState.Connected)))
    }

    /**
     * The case of the device test of 12.09.2026: while Bluetooth is off the service is the only listener for
     * it coming back, and no new `BLE_APPEARED` follows, so stopping here would end the app for good.
     */
    @Test
    fun `waiting for bluetooth keeps the service alive without a connection`() {
        assertNotNull(
            keepAliveReason(
                RemoteState(
                    serviceRunning = true,
                    connection = BleConnectionState.Failed(FailureReason.BluetoothDisabled),
                    waitingForBluetooth = true,
                ),
            ),
        )
        assertNotNull(
            keepAliveReason(RemoteState(serviceRunning = true, waitingForBluetooth = true)),
        )
    }

    /**
     * A remote that was just connected to for its confirmation stops advertising, so `BLE_DISAPPEARED` can
     * arrive seconds later. Stopping then would take away the only connection the confirmation press can
     * arrive on, and the user would press a button for nothing.
     */
    @Test
    fun `a running confirmation keeps the service alive`() {
        assertNotNull(
            keepAliveReason(
                RemoteState(
                    serviceRunning = true,
                    connection = BleConnectionState.Connecting,
                    pendingConfirmation = PendingConfirmation(
                        associationId = 7,
                        label = "Remote control-3D2C",
                        deadlineElapsedMs = 12_345_678L + CONFIRMATION_TIMEOUT_MS,
                    ),
                ),
            ),
        )
    }

    /** Three distinct reasons, so the device log says which one kept the service alive. */
    @Test
    fun `every reason has its own wording`() {
        val reasons = listOf(
            keepAliveReason(RemoteState(connection = BleConnectionState.Connected)),
            keepAliveReason(RemoteState(waitingForBluetooth = true)),
            keepAliveReason(
                RemoteState(
                    pendingConfirmation = PendingConfirmation(7, "Remote control-3D2C", 12_345_678L),
                ),
            ),
        )
        assertEquals(3, reasons.filterNotNull().toSet().size)
    }

    @Test
    fun `a remote that is really gone stops the service`() {
        listOf(
            BleConnectionState.Disconnected,
            BleConnectionState.Connecting,
            BleConnectionState.DiscoveringServices,
            BleConnectionState.EnablingNotifications,
            BleConnectionState.Reconnecting(1, 3, 2_000L, FailureReason.ConnectionLost(8)),
            BleConnectionState.Failed(FailureReason.ConnectionLost(8)),
        ).forEach {
            assertNull("$it", keepAliveReason(RemoteState(serviceRunning = true, connection = it)))
        }
    }
}
