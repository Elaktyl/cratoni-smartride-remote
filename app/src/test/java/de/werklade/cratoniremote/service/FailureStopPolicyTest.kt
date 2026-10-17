package de.werklade.cratoniremote.service

import de.werklade.cratoniremote.ble.FailureReason
import de.werklade.cratoniremote.ble.GattStep
import de.werklade.cratoniremote.ble.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FailureStopPolicyTest {

    private val transient = listOf(
        FailureReason.GattError(GattStep.CONNECT, 133),
        FailureReason.ConnectionLost(8),
        FailureReason.OperationNotStarted(GattStep.DISCOVER_SERVICES),
        FailureReason.Timeout(GattStep.ENABLE_NOTIFICATIONS),
    )

    private val permanent = listOf(
        FailureReason.BluetoothAdapterMissing,
        FailureReason.BluetoothDisabled,
        FailureReason.MissingPermission,
        FailureReason.DeviceUnsuitable("service FFF0"),
    )

    @Test
    fun `exhausted attempts after a transient failure stop the service`() {
        transient.forEach {
            assertEquals("$it", FailureStopPolicy.FailureAction.STOP, FailureStopPolicy.actionFor(it))
        }
    }

    @Test
    fun `failures that would recur immediately keep the service up`() {
        permanent.forEach {
            assertNotEquals("$it", FailureStopPolicy.FailureAction.STOP, FailureStopPolicy.actionFor(it))
        }
    }

    /**
     * The reason of the device test of 12.09.2026: nobody else notices Bluetooth coming back, so the service
     * has to stay up — but as "waiting", not as a permanent error.
     */
    @Test
    fun `bluetooth being off makes the service wait for the adapter`() {
        assertEquals(
            FailureStopPolicy.FailureAction.WAIT_FOR_BLUETOOTH,
            FailureStopPolicy.actionFor(FailureReason.BluetoothDisabled),
        )
    }

    @Test
    fun `failures the user has to act on keep the error visible`() {
        listOf(
            FailureReason.BluetoothAdapterMissing,
            FailureReason.MissingPermission,
            FailureReason.DeviceUnsuitable("service FFF0"),
        ).forEach {
            assertEquals("$it", FailureStopPolicy.FailureAction.KEEP_ERROR_VISIBLE, FailureStopPolicy.actionFor(it))
        }
    }

    /** Only [FailureReason.BluetoothDisabled] resolves itself, so only it may lead to waiting. */
    @Test
    fun `no other reason makes the service wait for the adapter`() {
        (transient + permanent)
            .filter { it != FailureReason.BluetoothDisabled }
            .forEach {
                assertNotEquals(
                    "$it",
                    FailureStopPolicy.FailureAction.WAIT_FOR_BLUETOOTH,
                    FailureStopPolicy.actionFor(it),
                )
            }
    }

    /** Guards the delegation: a reason a reconnect cannot fix must never lead to a restart either. */
    @Test
    fun `the stop decision matches ReconnectPolicy retryability for every reason`() {
        val policy = ReconnectPolicy()
        (transient + permanent).forEach {
            assertEquals(
                "$it",
                policy.isRetryable(it),
                FailureStopPolicy.actionFor(it) == FailureStopPolicy.FailureAction.STOP,
            )
        }
    }
}
