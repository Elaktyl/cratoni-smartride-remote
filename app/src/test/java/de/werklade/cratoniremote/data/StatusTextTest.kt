package de.werklade.cratoniremote.data

import de.werklade.cratoniremote.R
import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.FailureReason
import de.werklade.cratoniremote.ble.GattStep
import de.werklade.cratoniremote.protocol.RemoteCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which string resource says what about the remote — asserted on the [TextSpec]s, not on wording: the words
 * live in `res/values/strings.xml` and `res/values-de/strings.xml`, this decides which of them is shown.
 *
 * That makes the priority of the states, the arguments inside a text and the nesting of texts testable without
 * Android, exactly as the German literals of the former `RemoteStatusText` were.
 */
class StatusTextTest {

    private val pending = PendingConfirmation(
        associationId = 7,
        label = "Remote control-3D2C",
        deadlineElapsedMs = 12_345_678L + CONFIRMATION_TIMEOUT_MS,
    )

    // Connection state

    @Test
    fun `every connection state has its own text`() {
        val states = listOf(
            BleConnectionState.Disconnected,
            BleConnectionState.Connecting,
            BleConnectionState.DiscoveringServices,
            BleConnectionState.EnablingNotifications,
            BleConnectionState.Connected,
            BleConnectionState.Reconnecting(1, 3, 2_000L, FailureReason.ConnectionLost(8)),
            BleConnectionState.Failed(FailureReason.BluetoothDisabled),
        )
        val specs = states.map { StatusText.connection(it) }
        assertEquals(states.size, specs.toSet().size)
        assertTrue(specs.none { it.id == 0 })
    }

    @Test
    fun `reconnecting carries attempt, maximum and delay in whole seconds`() {
        val state = BleConnectionState.Reconnecting(2, 3, 5_000L, FailureReason.ConnectionLost(8))
        assertEquals(
            TextSpec(R.string.remote_status_reconnecting, listOf(2, 3, 5)),
            StatusText.connection(state),
        )
    }

    /** The error text carries the reason, and the reason the step it happened in: two levels, resolved inside out. */
    @Test
    fun `a failed connection carries the reason, and the reason the step`() {
        assertEquals(
            TextSpec(R.string.remote_status_error, listOf(TextSpec(R.string.failure_bluetooth_disabled))),
            StatusText.connection(BleConnectionState.Failed(FailureReason.BluetoothDisabled)),
        )
        assertEquals(
            TextSpec(
                R.string.remote_status_error,
                listOf(TextSpec(R.string.failure_gatt_error, listOf(TextSpec(R.string.gatt_step_connect)))),
            ),
            StatusText.connection(BleConnectionState.Failed(FailureReason.GattError(GattStep.CONNECT, 133))),
        )
    }

    @Test
    fun `every failure reason has its own text`() {
        val reasons = listOf(
            FailureReason.BluetoothAdapterMissing,
            FailureReason.BluetoothDisabled,
            FailureReason.MissingPermission,
            FailureReason.GattError(GattStep.CONNECT, 133),
            FailureReason.ConnectionLost(8),
            FailureReason.OperationNotStarted(GattStep.DISCOVER_SERVICES),
            FailureReason.Timeout(GattStep.ENABLE_NOTIFICATIONS),
            FailureReason.DeviceUnsuitable("service FFF0"),
        )
        val specs = reasons.map { StatusText.failure(it) }
        assertEquals(reasons.size, specs.toSet().size)
        assertTrue(specs.none { it.id == 0 })
    }

    /** The one failure that names a value of its own: what the device is missing. */
    @Test
    fun `an unsuitable device names what it is missing`() {
        assertEquals(
            TextSpec(R.string.failure_device_unsuitable, listOf("service FFF0")),
            StatusText.failure(FailureReason.DeviceUnsuitable("service FFF0")),
        )
    }

    @Test
    fun `every step of the connection setup has its own text`() {
        val specs = GattStep.entries.map { StatusText.gattStep(it) }
        assertEquals(GattStep.entries.size, specs.toSet().size)
        assertTrue(specs.none { it.id == 0 })
    }

    // Commands

    @Test
    fun `every command has its own text`() {
        val specs = RemoteCommand.entries.map { StatusText.command(it) }
        assertEquals(RemoteCommand.entries.size, specs.toSet().size)
        assertTrue(specs.none { it.id == 0 })
    }

    /** The walkie-talkie button starts the voice assistant, so the text names what it does, not the key. */
    @Test
    fun `WALKIE_TALKIE uses the voice assistant text`() {
        assertEquals(TextSpec(R.string.command_walkie_talkie), StatusText.command(RemoteCommand.WALKIE_TALKIE))
    }

    // State line

    @Test
    fun `remote line without an association says the remote is not set up`() {
        assertEquals(
            TextSpec(R.string.remote_status_not_configured),
            StatusText.remoteLine(remoteConfigured = false, state = RemoteState()),
        )
    }

    @Test
    fun `remote line with an association but without a running service says out of range`() {
        assertEquals(
            TextSpec(R.string.remote_status_out_of_range),
            StatusText.remoteLine(remoteConfigured = true, state = RemoteState()),
        )
    }

    /** The idle state must not read like something the user has to fix, so it is not the "disconnected" text. */
    @Test
    fun `the out of range text is its own text, not the disconnected one`() {
        assertNotEquals(
            StatusText.connection(BleConnectionState.Disconnected),
            StatusText.remoteLine(remoteConfigured = true, state = RemoteState()),
        )
    }

    @Test
    fun `remote line shows the connection state while the service runs`() {
        val state = RemoteState(serviceRunning = true, connection = BleConnectionState.Connected)
        assertEquals(
            TextSpec(R.string.remote_status_connected),
            StatusText.remoteLine(remoteConfigured = true, state = state),
        )
    }

    @Test
    fun `remote line shows a failure of a stopped service instead of out of range`() {
        val state = RemoteState(serviceRunning = false, connection = BleConnectionState.Failed(FailureReason.MissingPermission))
        assertEquals(
            TextSpec(R.string.remote_status_error, listOf(TextSpec(R.string.failure_missing_permission))),
            StatusText.remoteLine(remoteConfigured = true, state = state),
        )
    }

    /**
     * While the press is being waited for, the line has to say what to do. The connection to the chosen remote
     * stands at that moment, so "Verbunden" would be true and useless, and the same holds for every other
     * state the connection or the adapter can be in.
     */
    @Test
    fun `remote line asks for the confirmation press before anything else`() {
        listOf(
            RemoteState(serviceRunning = true, connection = BleConnectionState.Connected),
            RemoteState(serviceRunning = true, connection = BleConnectionState.Connecting),
            RemoteState(
                serviceRunning = true,
                connection = BleConnectionState.Failed(FailureReason.BluetoothDisabled),
                waitingForBluetooth = true,
            ),
            // Service not running yet: the "out of range" branch must not win either.
            RemoteState(),
        ).forEach { state ->
            assertEquals(
                "$state",
                TextSpec(R.string.remote_status_confirming),
                StatusText.remoteLine(remoteConfigured = true, state = state.copy(pendingConfirmation = pending)),
            )
        }
    }

    /** Without an association there is nothing to confirm; that case cannot occur and must not be shown. */
    @Test
    fun `the confirmation does not outrank not being set up`() {
        val state = RemoteState(pendingConfirmation = pending)
        assertEquals(
            TextSpec(R.string.remote_status_not_configured),
            StatusText.remoteLine(remoteConfigured = false, state = state),
        )
    }

    /** It says what the user has to do, so it is neither the idle text nor an error. */
    @Test
    fun `the confirmation text is its own text`() {
        val confirming = StatusText.remoteLine(remoteConfigured = true, state = RemoteState(pendingConfirmation = pending))
        assertNotEquals(StatusText.remoteLine(remoteConfigured = true, state = RemoteState()), confirming)
        assertNotEquals(
            StatusText.connection(BleConnectionState.Failed(FailureReason.BluetoothDisabled)),
            confirming,
        )
    }

    @Test
    fun `remote line shows that the service waits for bluetooth`() {
        val state = RemoteState(
            serviceRunning = true,
            connection = BleConnectionState.Failed(FailureReason.BluetoothDisabled),
            waitingForBluetooth = true,
        )
        assertEquals(
            TextSpec(R.string.remote_status_waiting_for_bluetooth),
            StatusText.remoteLine(remoteConfigured = true, state = state),
        )
    }

    /**
     * The screen and the notification must never say different things about the same state. They go through
     * this one function, and the waiting state is the only place where they are allowed to differ — the
     * notification stands alone in the status bar and says it in a full sentence.
     */
    @Test
    fun `screen and notification differ in the waiting state only`() {
        val states = listOf(
            RemoteState(),
            RemoteState(serviceRunning = true, connection = BleConnectionState.Connected),
            RemoteState(serviceRunning = true, connection = BleConnectionState.Connecting),
            RemoteState(serviceRunning = true, connection = BleConnectionState.DiscoveringServices),
            RemoteState(serviceRunning = true, connection = BleConnectionState.EnablingNotifications),
            RemoteState(serviceRunning = true, connection = BleConnectionState.Reconnecting(1, 3, 2_000L, FailureReason.ConnectionLost(8))),
            RemoteState(serviceRunning = true, connection = BleConnectionState.Failed(FailureReason.MissingPermission)),
            RemoteState(serviceRunning = true, pendingConfirmation = pending),
            RemoteState(serviceRunning = true, waitingForBluetooth = true),
        )
        states.forEach { state ->
            listOf(true, false).forEach { configured ->
                val screen = StatusText.remoteLine(configured, state)
                val notification =
                    StatusText.remoteLine(configured, state, R.string.notification_waiting_for_bluetooth)
                if (configured && state.waitingForBluetooth && state.pendingConfirmation == null) {
                    assertEquals("$state", TextSpec(R.string.remote_status_waiting_for_bluetooth), screen)
                    assertEquals("$state", TextSpec(R.string.notification_waiting_for_bluetooth), notification)
                } else {
                    assertEquals("$state", screen, notification)
                }
            }
        }
    }

    // Resolution

    /** A nested spec is looked up first, so the outer text formats a text and not a resource number. */
    @Test
    fun `resolve looks up the arguments before the text around them`() {
        val spec = TextSpec(
            R.string.remote_status_error,
            listOf(TextSpec(R.string.failure_gatt_error, listOf(TextSpec(R.string.gatt_step_connect)))),
        )
        val lookup: StringLookup = { id, args ->
            val label = when (id) {
                R.string.remote_status_error -> "error: %1\$s"
                R.string.failure_gatt_error -> "gatt error while %1\$s"
                R.string.gatt_step_connect -> "connecting"
                else -> error("unexpected resource $id")
            }
            if (args.isEmpty()) label else String.format(label, *args)
        }
        assertEquals("error: gatt error while connecting", spec.resolve(lookup))
    }

    @Test
    fun `resolve passes a text without arguments through`() {
        val spec = TextSpec(R.string.remote_status_connected)
        assertEquals("connected", spec.resolve { id, _ -> if (id == spec.id) "connected" else error("unexpected $id") })
    }
}
