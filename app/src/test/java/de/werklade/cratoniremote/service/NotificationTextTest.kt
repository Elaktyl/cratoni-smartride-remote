package de.werklade.cratoniremote.service

import de.werklade.cratoniremote.R
import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.FailureReason
import de.werklade.cratoniremote.ble.RemoteBattery
import de.werklade.cratoniremote.data.LastCommand
import de.werklade.cratoniremote.data.RemoteState
import de.werklade.cratoniremote.data.StringLookup
import de.werklade.cratoniremote.data.TextSpec
import de.werklade.cratoniremote.protocol.RemoteCommand
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one line of the foreground service notification: which fields, in which order, and which of them are
 * left out.
 *
 * The wording comes from string resources, which a local unit test has no access to, so [lookup] stands in for
 * `Resources.getString` with labels that are obviously fakes — what is asserted here is the assembly, not the
 * translation. That one is lint's job (`MissingTranslation`) and the device test's.
 */
class NotificationTextTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private val german = Locale.GERMANY

    private val us = Locale.US

    /** German writes a non-breaking space in front of the per cent sign; escaped so no formatter can eat it. */
    private val nbsp = "\u00A0"

    /** 12.09.2026, 12:34:56 Europe/Berlin (CEST, UTC+2). */
    private val at = 1_789_209_296_000L

    /** A minute after [at]: the reference "now", so no assertion depends on the clock of the machine. */
    private val shortlyAfter = at + 60_000L

    /** The next morning, well past [de.werklade.cratoniremote.data.RemoteStatusText.DATE_SHOWN_AFTER_MS]. */
    private val nextDay = at + 20 * 60 * 60 * 1000L

    private val lastCommand = LastCommand(RemoteCommand.NEXT, at)

    /** One of the measured status frames: `50 0F 42`. */
    private val battery = RemoteBattery(percent = 80, millivolts = 3_906, atEpochMs = at)

    /** Fake labels instead of the real wording; the arguments are formatted in as the resources would. */
    private val labels = mapOf(
        R.string.remote_status_not_configured to "not-set-up",
        R.string.remote_status_out_of_range to "out-of-range",
        R.string.remote_status_disconnected to "disconnected",
        R.string.remote_status_connecting to "connecting",
        R.string.remote_status_connected to "connected",
        R.string.remote_status_confirming to "confirming",
        R.string.remote_status_error to "error[%1\$s]",
        R.string.failure_bluetooth_disabled to "bluetooth-off",
        R.string.failure_connection_lost to "connection-lost",
        R.string.notification_waiting_for_bluetooth to "waiting-for-bluetooth",
        R.string.notification_battery to "battery[%1\$s]",
        R.string.notification_last_command to "last-command[%1\$s, %2\$s]",
        R.string.command_next to "next-track",
    )

    private val lookup: StringLookup = { id, args ->
        val label = labels[id] ?: error("no fake label for resource $id")
        if (args.isEmpty()) label else String.format(Locale.ROOT, label, *args)
    }

    private fun line(
        state: RemoteState,
        nowEpochMs: Long = shortlyAfter,
        locale: Locale = german,
    ): String = NotificationText.line(lookup, state, nowEpochMs, zone, locale)

    @Test
    fun `only the connection state is shown while battery and command are unknown`() {
        val state = RemoteState(serviceRunning = true, connection = BleConnectionState.Connected)
        assertEquals("connected", line(state))
        assertEquals(listOf(TextSpec(R.string.remote_status_connected)), NotificationText.fields(state, shortlyAfter, zone, german))
    }

    @Test
    fun `battery and last command follow the state, in that order`() {
        val state = RemoteState(
            serviceRunning = true,
            connection = BleConnectionState.Connected,
            battery = battery,
            lastCommand = lastCommand,
        )
        assertEquals(
            "connected · battery[80$nbsp% (3,91 V)] · last-command[next-track, 12:34:56]",
            line(state),
        )
    }

    @Test
    fun `the last command stays without a battery level`() {
        val state = RemoteState(serviceRunning = true, connection = BleConnectionState.Connecting, lastCommand = lastCommand)
        assertEquals("connecting · last-command[next-track, 12:34:56]", line(state))
    }

    /** Without a measurement the field is left out, not filled with a placeholder: the line must stay short. */
    @Test
    fun `a field without a value is left out`() {
        val state = RemoteState(serviceRunning = true, connection = BleConnectionState.Connected, battery = battery)
        assertEquals("connected · battery[80$nbsp% (3,91 V)]", line(state))
        assertTrue(
            NotificationText.fields(state, shortlyAfter, zone, german)
                .none { it.id == R.string.notification_last_command },
        )
    }

    /** Without the date the command of the previous evening would look like one from a few minutes ago. */
    @Test
    fun `the last command of the day before carries the date`() {
        val state = RemoteState(serviceRunning = true, connection = BleConnectionState.Connected, lastCommand = lastCommand)
        assertEquals("connected · last-command[next-track, 12.09.2026, 12:34:56]", line(state, nowEpochMs = nextDay))
    }

    /** Battery Level 0x2A19 carries no voltage; the field must still read properly. */
    @Test
    fun `the battery field without a voltage shows the percentage alone`() {
        val state = RemoteState(serviceRunning = true, connection = BleConnectionState.Connected, battery = battery.copy(millivolts = null))
        assertEquals("connected · battery[80$nbsp%]", line(state))
    }

    /**
     * Numbers and times follow the locale the notification is built for, which is the one its resources were
     * resolved with — never the default locale of the machine. Otherwise one line would carry two conventions.
     */
    @Test
    fun `numbers and times follow the locale of the notification, not the default locale`() {
        val state = RemoteState(
            serviceRunning = true,
            connection = BleConnectionState.Connected,
            battery = battery,
            lastCommand = lastCommand,
        )
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals(
                "connected · battery[80$nbsp% (3,91 V)] · last-command[next-track, 12:34:56]",
                line(state, locale = german),
            )
            Locale.setDefault(Locale.GERMANY)
            val american = line(state, locale = us)
            assertTrue(american, american.contains("battery[80% (3.91 V)]"))
            assertTrue(american, american.contains("last-command[next-track, 12:34:56"))
            assertTrue(american, american.endsWith("PM]"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** The separator separates the fields; inside a field it would blur the levels. */
    @Test
    fun `the battery field does not contain the field separator`() {
        val state = RemoteState(serviceRunning = true, connection = BleConnectionState.Connected, battery = battery)
        assertEquals(2, line(state).split(NotificationText.SEPARATOR).size)
        assertEquals(3, line(state.copy(lastCommand = lastCommand)).split(NotificationText.SEPARATOR).size)
    }

    @Test
    fun `the line stays a single line`() {
        val state = RemoteState(
            serviceRunning = true,
            connection = BleConnectionState.Failed(FailureReason.ConnectionLost(8)),
            battery = battery.copy(percent = 5, millivolts = 3_600),
            lastCommand = lastCommand,
        )
        assertFalse(line(state).contains('\n'))
    }

    @Test
    fun `waiting for bluetooth replaces the connection state`() {
        val state = RemoteState(
            serviceRunning = true,
            connection = BleConnectionState.Disconnected,
            waitingForBluetooth = true,
        )
        assertEquals("waiting-for-bluetooth", line(state))
    }

    /** The leftover failure of an attempt that ran into the adapter being switched off must not be shown. */
    @Test
    fun `waiting for bluetooth replaces a failed connection state and keeps the other fields`() {
        val state = RemoteState(
            serviceRunning = true,
            connection = BleConnectionState.Failed(FailureReason.BluetoothDisabled),
            waitingForBluetooth = true,
            lastCommand = lastCommand,
        )
        assertEquals("waiting-for-bluetooth · last-command[next-track, 12:34:56]", line(state))
    }

    /**
     * The waiting state has a resource of its own, longer than the one on the screen: the notification stands
     * alone in the status bar, and it must not read like an error the user has to fix.
     */
    @Test
    fun `the waiting field is the notification's own resource`() {
        val state = RemoteState(serviceRunning = true, waitingForBluetooth = true)
        assertEquals(
            listOf(TextSpec(R.string.notification_waiting_for_bluetooth)),
            NotificationText.fields(state, shortlyAfter, zone, german),
        )
    }

    /**
     * The first notification is built before [de.werklade.cratoniremote.data.RemoteStatus] knows that the
     * service started ([RemoteConnectionService]), so it must not fall into the "not in range" branch.
     */
    @Test
    fun `the connection state is shown even before the service is marked as running`() {
        val state = RemoteState(serviceRunning = false, connection = BleConnectionState.Disconnected)
        assertEquals("disconnected", line(state))
    }

    /** A notification only exists while a remote is set up, so that branch can never be reached. */
    @Test
    fun `the line never says the remote is not set up`() {
        assertEquals("disconnected", line(RemoteState()))
    }
}
