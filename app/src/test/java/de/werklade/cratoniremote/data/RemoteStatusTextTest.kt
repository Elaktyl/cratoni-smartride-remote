package de.werklade.cratoniremote.data

import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.FailureReason
import de.werklade.cratoniremote.ble.GattStep
import de.werklade.cratoniremote.ble.RemoteBattery
import de.werklade.cratoniremote.protocol.RemoteCommand
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStatusTextTest {

    private val zone = ZoneId.of("Europe/Berlin")

    /** 12.09.2026, 12:34:56 Europe/Berlin (CEST, UTC+2), i.e. 10:34:56 UTC. */
    private val at = 1_789_209_296_000L

    /** A minute after [at]: the reference "now" wherever a text must not depend on the clock of the machine. */
    private val shortlyAfter = at + 60_000L

    /** The next morning, well past [RemoteStatusText.DATE_SHOWN_AFTER_MS]. */
    private val nextDay = at + 20 * 60 * 60 * 1000L

    private val lastCommand = LastCommand(RemoteCommand.NEXT, at)

    /** One of the measured status frames: `50 0F 42`. */
    private val battery = RemoteBattery(percent = 80, millivolts = 3_906, atEpochMs = at)

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
        val texts = states.map { RemoteStatusText.connection(it) }
        assertEquals(states.size, texts.toSet().size)
        assertTrue(texts.none { it.isBlank() })
    }

    @Test
    fun `reconnecting shows attempt, maximum and delay in seconds`() {
        val state = BleConnectionState.Reconnecting(2, 3, 5_000L, FailureReason.ConnectionLost(8))
        assertEquals("Neuer Versuch 2/3 in 5 s", RemoteStatusText.connection(state))
    }

    @Test
    fun `failure shows the reason`() {
        assertEquals("Fehler: Bluetooth ist aus", RemoteStatusText.connection(BleConnectionState.Failed(FailureReason.BluetoothDisabled)))
        assertEquals(
            "Fehler: GATT-Status 133 beim Verbinden",
            RemoteStatusText.connection(BleConnectionState.Failed(FailureReason.GattError(GattStep.CONNECT, 133))),
        )
    }

    @Test
    fun `every failure reason has a non blank text`() {
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
        val texts = reasons.map { RemoteStatusText.failure(it) }
        assertEquals(reasons.size, texts.toSet().size)
        assertTrue(texts.none { it.isBlank() })
    }

    // Commands, battery, time

    @Test
    fun `every command has its own text`() {
        val texts = RemoteCommand.entries.map { RemoteStatusText.command(it) }
        assertEquals(RemoteCommand.entries.size, texts.toSet().size)
        assertTrue(texts.none { it.isBlank() })
    }

    /** The walkie-talkie button starts the voice assistant, so the text names what it does, not the key. */
    @Test
    fun `WALKIE_TALKIE reads as Sprachassistent`() {
        assertEquals("Sprachassistent", RemoteStatusText.command(RemoteCommand.WALKIE_TALKIE))
    }

    @Test
    fun `battery is shown with percent and voltage, or as unknown`() {
        assertEquals("Akku: unbekannt", RemoteStatusText.battery(null))
        assertEquals("Akku: 80 % (3,91 V)", RemoteStatusText.battery(battery))
        assertEquals("Akku: 0 % (3,74 V)", RemoteStatusText.battery(battery.copy(percent = 0, millivolts = 3_736)))
        assertEquals("Akku: 100 % (4,09 V)", RemoteStatusText.battery(battery.copy(percent = 100, millivolts = 4_094)))
    }

    /** The separator only separates the fields of the notification; inside a field it would blur the levels. */
    @Test
    fun `the battery field does not contain the field separator`() {
        assertFalse(RemoteStatusText.battery(battery).contains(RemoteStatusText.SEPARATOR))
        assertFalse(RemoteStatusText.batteryValue(battery).contains(RemoteStatusText.SEPARATOR))
    }

    @Test
    fun `the battery value is percent and the voltage in brackets`() {
        assertEquals("60 % (3,74 V)", RemoteStatusText.batteryValue(battery.copy(percent = 60, millivolts = 3_736)))
        assertEquals("90 % (4,09 V)", RemoteStatusText.batteryValue(battery.copy(percent = 90, millivolts = 4_094)))
    }

    @Test
    fun `without a measurement the battery value is unknown`() {
        assertEquals(RemoteStatusText.BATTERY_UNKNOWN, RemoteStatusText.batteryValue(null))
    }

    /** Battery Level 0x2A19 carries no voltage; the percentage alone must still read properly. */
    @Test
    fun `without a voltage only the percentage is shown`() {
        assertEquals("80 %", RemoteStatusText.batteryValue(battery.copy(millivolts = null)))
        assertEquals("Akku: 80 %", RemoteStatusText.battery(battery.copy(millivolts = null)))
    }

    @Test
    fun `volts are rounded to two decimals with a decimal comma`() {
        assertEquals("3,74", RemoteStatusText.volts(3_736))
        assertEquals("3,91", RemoteStatusText.volts(3_906))
        assertEquals("3,82", RemoteStatusText.volts(3_821))
        assertEquals("4,09", RemoteStatusText.volts(4_094))
        assertEquals("4,00", RemoteStatusText.volts(4_000))
    }

    /** The format must not follow the locale of the device: a German text with "3.74" in it would be wrong. */
    @Test
    fun `volts keep the decimal comma under a foreign default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("3,74", RemoteStatusText.volts(3_736))
            assertEquals("80 % (3,91 V)", RemoteStatusText.batteryValue(battery))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the measurement time is an hour and a minute in the given zone`() {
        assertEquals("12:34", RemoteStatusText.shortTimestamp(at, shortlyAfter, zone))
        assertEquals("10:34", RemoteStatusText.shortTimestamp(at, shortlyAfter, ZoneId.of("UTC")))
    }

    /**
     * A connection can stand across midnight and the poll stands still in deep sleep, so a measurement can be
     * hours old while it is shown; then "gemessen um 23:58" alone would be misleading.
     */
    @Test
    fun `the measurement time adds the date once it is half a day old`() {
        val exactly = at + RemoteStatusText.DATE_SHOWN_AFTER_MS
        assertEquals("12.09.2026, 12:34", RemoteStatusText.shortTimestamp(at, exactly, zone))
        assertEquals("12.09.2026, 12:34", RemoteStatusText.shortTimestamp(at, nextDay, zone))
    }

    /** Never with seconds: the second of a five minute poll says nothing. */
    @Test
    fun `the measurement time has no seconds in either form`() {
        assertFalse(RemoteStatusText.shortTimestamp(at, shortlyAfter, zone).contains(":56"))
        assertFalse(RemoteStatusText.shortTimestamp(at, nextDay, zone).contains(":56"))
    }

    @Test
    fun `time is formatted as hours, minutes and seconds in the given zone`() {
        assertEquals("12:34:56", RemoteStatusText.time(at, zone))
        assertEquals("10:34:56", RemoteStatusText.time(at, ZoneId.of("UTC")))
    }

    @Test
    fun `timestamp stays a time of day while the command is recent`() {
        assertEquals("12:34:56", RemoteStatusText.timestamp(at, shortlyAfter, zone))
        assertFalse(RemoteStatusText.showsDate(at, shortlyAfter))
    }

    @Test
    fun `timestamp adds the date once the command is half a day old`() {
        val exactly = at + RemoteStatusText.DATE_SHOWN_AFTER_MS
        assertTrue(RemoteStatusText.showsDate(at, exactly))
        assertEquals("12.09.2026, 12:34:56", RemoteStatusText.timestamp(at, exactly, zone))
        assertEquals("12.09.2026, 12:34:56", RemoteStatusText.timestamp(at, nextDay, zone))
    }

    @Test
    fun `just below half a day the date is still left out`() {
        val justBefore = at + RemoteStatusText.DATE_SHOWN_AFTER_MS - 1
        assertFalse(RemoteStatusText.showsDate(at, justBefore))
        assertEquals("12:34:56", RemoteStatusText.timestamp(at, justBefore, zone))
    }

    @Test
    fun `last command shows name and time, or a placeholder`() {
        assertEquals("Letzter Befehl: ${RemoteStatusText.NONE}", RemoteStatusText.lastCommand(null, shortlyAfter, zone))
        assertEquals("Letzter Befehl: Nächster Titel (12:34:56)", RemoteStatusText.lastCommand(lastCommand, shortlyAfter, zone))
    }

    /** Without the date the command of the previous evening would look like one from a few minutes ago. */
    @Test
    fun `last command of the day before shows the date as well`() {
        assertEquals(
            "Letzter Befehl: Nächster Titel (12.09.2026, 12:34:56)",
            RemoteStatusText.lastCommand(lastCommand, nextDay, zone),
        )
    }

    // Notification

    @Test
    fun `notification shows only the connection state while battery and command are unknown`() {
        val state = RemoteState(serviceRunning = true, connection = BleConnectionState.Connected)
        assertEquals("Verbunden", RemoteStatusText.notification(state, shortlyAfter, zone))
    }

    @Test
    fun `notification adds battery and last command once they are known`() {
        val state = RemoteState(
            serviceRunning = true,
            connection = BleConnectionState.Connected,
            battery = battery,
            lastCommand = lastCommand,
        )
        assertEquals(
            "Verbunden · Akku: 80 % (3,91 V) · Letzter Befehl: Nächster Titel (12:34:56)",
            RemoteStatusText.notification(state, shortlyAfter, zone),
        )
    }

    @Test
    fun `notification keeps the last command without a battery level`() {
        val state = RemoteState(serviceRunning = true, connection = BleConnectionState.Connecting, lastCommand = lastCommand)
        assertEquals(
            "Verbindung wird aufgebaut · Letzter Befehl: Nächster Titel (12:34:56)",
            RemoteStatusText.notification(state, shortlyAfter, zone),
        )
    }

    @Test
    fun `notification says the service waits for bluetooth instead of showing a state`() {
        val state = RemoteState(
            serviceRunning = true,
            connection = BleConnectionState.Disconnected,
            waitingForBluetooth = true,
        )
        assertEquals(RemoteStatusText.WAITING_FOR_BLUETOOTH, RemoteStatusText.notification(state, shortlyAfter, zone))
    }

    /** The leftover failure of an attempt that ran into the adapter being switched off must not be shown. */
    @Test
    fun `waiting for bluetooth replaces a failed connection state`() {
        val state = RemoteState(
            serviceRunning = true,
            connection = BleConnectionState.Failed(FailureReason.BluetoothDisabled),
            waitingForBluetooth = true,
            lastCommand = lastCommand,
        )
        assertEquals(
            "${RemoteStatusText.WAITING_FOR_BLUETOOTH} · Letzter Befehl: Nächster Titel (12:34:56)",
            RemoteStatusText.notification(state, shortlyAfter, zone),
        )
    }

    @Test
    fun `the waiting text is not an error message`() {
        assertTrue(RemoteStatusText.WAITING_FOR_BLUETOOTH.isNotBlank())
        assertFalse(RemoteStatusText.WAITING_FOR_BLUETOOTH.contains("Fehler"))
    }

    @Test
    fun `notification stays a single line`() {
        val state = RemoteState(
            serviceRunning = true,
            connection = BleConnectionState.Failed(FailureReason.ConnectionLost(8)),
            battery = battery.copy(percent = 5, millivolts = 3_600),
            lastCommand = lastCommand,
        )
        assertTrue(RemoteStatusText.notification(state, shortlyAfter, zone).none { it == '\n' })
    }

    // State line (shared by the notification and, as string resources, by the screen)

    @Test
    fun `remote line without an association says the remote is not set up`() {
        assertEquals(RemoteStatusText.NOT_CONFIGURED, RemoteStatusText.remoteLine(remoteConfigured = false, state = RemoteState()))
    }

    @Test
    fun `remote line with an association but without a running service says out of range`() {
        assertEquals(RemoteStatusText.OUT_OF_RANGE, RemoteStatusText.remoteLine(remoteConfigured = true, state = RemoteState()))
    }

    /** The idle state must not read like something the user has to fix. */
    @Test
    fun `the out of range text is not an error message`() {
        assertTrue(RemoteStatusText.OUT_OF_RANGE.isNotBlank())
        assertFalse(RemoteStatusText.OUT_OF_RANGE.contains("Fehler"))
        assertNotEquals(RemoteStatusText.connection(BleConnectionState.Disconnected), RemoteStatusText.OUT_OF_RANGE)
    }

    @Test
    fun `remote line shows the connection state while the service runs`() {
        val state = RemoteState(serviceRunning = true, connection = BleConnectionState.Connected)
        assertEquals("Verbunden", RemoteStatusText.remoteLine(remoteConfigured = true, state = state))
    }

    @Test
    fun `remote line shows a failure of a stopped service instead of out of range`() {
        val state = RemoteState(serviceRunning = false, connection = BleConnectionState.Failed(FailureReason.MissingPermission))
        assertEquals("Fehler: Berechtigung fehlt", RemoteStatusText.remoteLine(remoteConfigured = true, state = state))
    }

    /**
     * While the press is being waited for, the line has to say what to do. The connection to the chosen remote
     * stands at that moment, so "Verbunden" would be true and useless, and the same holds for every other
     * state the connection or the adapter can be in.
     */
    @Test
    fun `remote line asks for the confirmation press before anything else`() {
        val pending = PendingConfirmation(
            associationId = 7,
            label = "Remote control-3D2C",
            deadlineElapsedMs = 12_345_678L + CONFIRMATION_TIMEOUT_MS,
        )
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
                RemoteStatusText.CONFIRMING,
                RemoteStatusText.remoteLine(remoteConfigured = true, state = state.copy(pendingConfirmation = pending)),
            )
        }
    }

    /** Without an association there is nothing to confirm; that case cannot occur and must not be shown. */
    @Test
    fun `the confirmation does not outrank not being set up`() {
        val state = RemoteState(
            pendingConfirmation = PendingConfirmation(7, "Remote control-3D2C", 12_345_678L),
        )
        assertEquals(RemoteStatusText.NOT_CONFIGURED, RemoteStatusText.remoteLine(remoteConfigured = false, state = state))
    }

    @Test
    fun `the confirmation text says what the user has to do and is not an error`() {
        assertTrue(RemoteStatusText.CONFIRMING.isNotBlank())
        assertFalse(RemoteStatusText.CONFIRMING.contains("Fehler"))
        assertNotEquals(RemoteStatusText.CONFIRMING, RemoteStatusText.OUT_OF_RANGE)
    }

    @Test
    fun `remote line shows that the service waits for bluetooth`() {
        val state = RemoteState(
            serviceRunning = true,
            connection = BleConnectionState.Failed(FailureReason.BluetoothDisabled),
            waitingForBluetooth = true,
        )
        assertEquals(RemoteStatusText.WAITING_FOR_BLUETOOTH, RemoteStatusText.remoteLine(remoteConfigured = true, state = state))
    }

    /**
     * The first notification is built before [RemoteStatus] knows that the service started
     * (`RemoteConnectionService.startForegroundSafely()`), so the notification must not fall into the
     * "not in range" branch of [RemoteStatusText.remoteLine].
     */
    @Test
    fun `notification shows the connection state even before the service is marked as running`() {
        val state = RemoteState(serviceRunning = false, connection = BleConnectionState.Disconnected)
        assertEquals("Nicht verbunden", RemoteStatusText.notification(state, shortlyAfter, zone))
    }
}
