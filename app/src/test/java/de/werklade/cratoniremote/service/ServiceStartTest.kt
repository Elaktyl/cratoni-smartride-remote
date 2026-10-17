package de.werklade.cratoniremote.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldStartConnectionService] closes the second hole the device test of 17.10.2026 showed: the pairing was
 * adopted while the remote was switched on and in range, and nothing started the connection because the system
 * had already reported that remote as present long before.
 */
class ServiceStartTest {

    @Test
    fun `a remote that applies without a running service is connected to`() {
        assertTrue(
            shouldStartConnectionService(
                applicableAssociationId = 10,
                serviceRunning = false,
                confirmationRunning = false,
                bluetoothEnabled = true,
                alreadyTriedFor = null,
            ),
        )
    }

    @Test
    fun `nothing is started without a remote that applies`() {
        assertFalse(
            shouldStartConnectionService(
                applicableAssociationId = null,
                serviceRunning = false,
                confirmationRunning = false,
                bluetoothEnabled = true,
                alreadyTriedFor = null,
            ),
        )
    }

    /** The presence path owns the running service; a second start would only be noise. */
    @Test
    fun `a running service is left alone`() {
        assertFalse(
            shouldStartConnectionService(
                applicableAssociationId = 10,
                serviceRunning = true,
                confirmationRunning = false,
                bluetoothEnabled = true,
                alreadyTriedFor = null,
            ),
        )
    }

    /** During a confirmation the service runs for exactly that association and must not be redirected. */
    @Test
    fun `nothing is started while a confirmation runs`() {
        assertFalse(
            shouldStartConnectionService(
                applicableAssociationId = 10,
                serviceRunning = false,
                confirmationRunning = true,
                bluetoothEnabled = true,
                alreadyTriedFor = null,
            ),
        )
    }

    /**
     * One attempt per applicable remote: the reconcile runs on every `ON_RESUME`, and a remote that is out of
     * range would otherwise be connected to again every time the user comes back to the screen.
     */
    @Test
    fun `the same remote is only tried once`() {
        assertFalse(
            shouldStartConnectionService(
                applicableAssociationId = 10,
                serviceRunning = false,
                confirmationRunning = false,
                bluetoothEnabled = true,
                alreadyTriedFor = 10,
            ),
        )
    }

    /**
     * With Bluetooth off the service would not fail but wait for the adapter, and that waiting is unbounded on
     * purpose — opening the app with Bluetooth switched off would leave a permanent notification behind.
     */
    @Test
    fun `nothing is started while bluetooth is off`() {
        assertFalse(
            shouldStartConnectionService(
                applicableAssociationId = 10,
                serviceRunning = false,
                confirmationRunning = false,
                bluetoothEnabled = false,
                alreadyTriedFor = null,
            ),
        )
    }

    /** A different remote (set up again after "vergessen") is a new attempt. */
    @Test
    fun `another remote is tried again`() {
        assertTrue(
            shouldStartConnectionService(
                applicableAssociationId = 11,
                serviceRunning = false,
                confirmationRunning = false,
                bluetoothEnabled = true,
                alreadyTriedFor = 10,
            ),
        )
    }
}
