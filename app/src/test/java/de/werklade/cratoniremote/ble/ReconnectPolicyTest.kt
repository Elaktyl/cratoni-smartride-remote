package de.werklade.cratoniremote.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    private val policy = ReconnectPolicy()
    private val lost = FailureReason.ConnectionLost(8)

    private val retryable = listOf(
        FailureReason.GattError(GattStep.CONNECT, 133),
        FailureReason.ConnectionLost(0),
        FailureReason.OperationNotStarted(GattStep.DISCOVER_SERVICES),
        FailureReason.Timeout(GattStep.ENABLE_NOTIFICATIONS),
    )

    private val notRetryable = listOf(
        FailureReason.BluetoothAdapterMissing,
        FailureReason.BluetoothDisabled,
        FailureReason.MissingPermission,
        FailureReason.DeviceUnsuitable("service FFF0"),
    )

    // Defaults

    @Test
    fun `defaults are 3 attempts with growing delays of 2 s, 5 s and 10 s`() {
        assertEquals(3, policy.maxAttempts)
        assertEquals(listOf(2_000L, 5_000L, 10_000L), (1..3).map { policy.delayBeforeAttempt(it) })
        assertEquals(30_000L, policy.stableConnectionMs)
    }

    @Test
    fun `delayBeforeAttempt is null outside the attempt range`() {
        assertNull(policy.delayBeforeAttempt(-1))
        assertNull(policy.delayBeforeAttempt(0))
        assertNull(policy.delayBeforeAttempt(4))
    }

    // Bounded attempts

    @Test
    fun `retryable failure yields attempts 1 to 3, then gives up`() {
        assertEquals(ReconnectDecision.Retry(1, 2_000L), policy.decide(lost, 0, null))
        assertEquals(ReconnectDecision.Retry(2, 5_000L), policy.decide(lost, 1, null))
        assertEquals(ReconnectDecision.Retry(3, 10_000L), policy.decide(lost, 2, null))
        assertEquals(ReconnectDecision.GiveUp, policy.decide(lost, 3, null))
    }

    @Test
    fun `GATT error, connection loss, operation not started and timeout are retried`() {
        retryable.forEach { reason ->
            assertTrue(reason.toString(), policy.isRetryable(reason))
            assertEquals(reason.toString(), ReconnectDecision.Retry(1, 2_000L), policy.decide(reason, 0, null))
        }
    }

    @Test
    fun `missing adapter, Bluetooth off, missing permission and unsuitable device give up immediately`() {
        notRetryable.forEach { reason ->
            assertFalse(reason.toString(), policy.isRetryable(reason))
            assertEquals(reason.toString(), ReconnectDecision.GiveUp, policy.decide(reason, 0, null))
        }
    }

    // Counter reset after a stable connection

    @Test
    fun `connection stable for 30 s resets the attempt counter`() {
        assertEquals(ReconnectDecision.Retry(1, 2_000L), policy.decide(lost, 3, 30_000L))
    }

    @Test
    fun `connection shorter than 30 s does not reset the attempt counter`() {
        assertEquals(ReconnectDecision.Retry(3, 10_000L), policy.decide(lost, 2, 29_999L))
        assertEquals(ReconnectDecision.GiveUp, policy.decide(lost, 3, 29_999L))
    }

    @Test
    fun `stable connection does not make a non-retryable failure retryable`() {
        notRetryable.forEach { reason ->
            assertEquals(reason.toString(), ReconnectDecision.GiveUp, policy.decide(reason, 0, 60_000L))
        }
    }

    // Custom configuration and argument checks

    @Test
    fun `custom delays define the number of attempts`() {
        val custom = ReconnectPolicy(listOf(100L), stableConnectionMs = 1_000L)
        assertEquals(1, custom.maxAttempts)
        assertEquals(ReconnectDecision.Retry(1, 100L), custom.decide(lost, 0, null))
        assertEquals(ReconnectDecision.GiveUp, custom.decide(lost, 1, null))
    }

    @Test
    fun `empty delay list disables reconnecting`() {
        assertEquals(ReconnectDecision.GiveUp, ReconnectPolicy(emptyList()).decide(lost, 0, null))
    }

    @Test
    fun `later changes to the passed delay list have no effect`() {
        val delays = mutableListOf(100L)
        val custom = ReconnectPolicy(delays)
        delays += 200L
        assertEquals(1, custom.maxAttempts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative delay is rejected`() {
        ReconnectPolicy(listOf(100L, -1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative stable connection threshold is rejected`() {
        ReconnectPolicy(stableConnectionMs = -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative attemptsUsed is rejected`() {
        policy.decide(lost, -1, null)
    }
}
