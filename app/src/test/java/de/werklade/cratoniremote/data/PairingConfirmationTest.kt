package de.werklade.cratoniremote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edges of the countdown of [PendingConfirmation]. "Now" is always passed in, so these tests do not depend on
 * the clock of the machine — on the device it is [android.os.SystemClock.elapsedRealtime].
 */
class PairingConfirmationTest {

    /** An arbitrary, large elapsed-realtime value: the device has been up for a while. */
    private val start = 12_345_678L

    private val pending = PendingConfirmation(
        associationId = 7,
        label = "Remote control-3D2C",
        deadlineElapsedMs = start + CONFIRMATION_TIMEOUT_MS,
    )

    @Test
    fun `the timeout is the agreed 30 seconds`() {
        assertEquals(30_000L, CONFIRMATION_TIMEOUT_MS)
    }

    @Test
    fun `at the start the full timeout is left`() {
        assertEquals(30, pending.remainingSeconds(start))
        assertFalse(pending.isExpired(start))
    }

    /** Rounded up, so the first tick does not jump from 30 to 29 after a single millisecond. */
    @Test
    fun `a started second is shown as a whole one`() {
        assertEquals(30, pending.remainingSeconds(start + 1))
        assertEquals(30, pending.remainingSeconds(start + 999))
        assertEquals(29, pending.remainingSeconds(start + 1_000))
        assertEquals(29, pending.remainingSeconds(start + 1_001))
    }

    @Test
    fun `one millisecond before the deadline one second is left and nothing is expired`() {
        val oneBefore = pending.deadlineElapsedMs - 1
        assertEquals(1, pending.remainingSeconds(oneBefore))
        assertFalse(pending.isExpired(oneBefore))
    }

    @Test
    fun `the deadline itself counts as expired`() {
        assertEquals(0, pending.remainingSeconds(pending.deadlineElapsedMs))
        assertTrue(pending.isExpired(pending.deadlineElapsedMs))
    }

    @Test
    fun `after the deadline the remaining time stays at zero and is never negative`() {
        listOf(1L, 1_000L, 60_000L, 10L * 24 * 60 * 60 * 1_000).forEach { past ->
            val now = pending.deadlineElapsedMs + past
            assertEquals("$past ms past the deadline", 0, pending.remainingSeconds(now))
            assertTrue("$past ms past the deadline", pending.isExpired(now))
        }
    }
}
