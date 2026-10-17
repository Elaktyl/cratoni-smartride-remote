package de.werklade.cratoniremote.data

import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.FailureReason
import de.werklade.cratoniremote.ble.GattStep
import de.werklade.cratoniremote.ble.RemoteBattery
import de.werklade.cratoniremote.protocol.RemoteCommand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns [RemoteState] into the German texts of the notification.
 *
 * Deliberately pure Kotlin without Android dependencies (no `Context`, no string resources), so the wording
 * is covered by local unit tests — the foreground service notification is the only place where the user sees
 * the connection state while the UI is closed. Since step 6 the screen has its own string resources
 * (`res/values/strings.xml`); only [timestamp] is shared with it, as a pure formatting helper.
 */
object RemoteStatusText {

    /** Separator between the parts of the one-line notification text. */
    const val SEPARATOR = " · "

    /** Placeholder where a value is missing, e.g. before the first command. */
    const val NONE = "—"

    /**
     * Shown while [RemoteState.waitingForBluetooth]: a factual statement, not an error. The service stays up in
     * this state on purpose — it is the only place that notices Bluetooth being switched on again.
     */
    const val WAITING_FOR_BLUETOOTH = "Bluetooth ist aus, wartet auf Aktivierung"

    /** No association: nothing is observed and nothing connects. Same wording as `R.string.remote_status_not_configured`. */
    const val NOT_CONFIGURED = "Nicht eingerichtet"

    /**
     * A remote was chosen in the association dialog and the press that confirms it is being waited for
     * ([RemoteState.pendingConfirmation]). Same wording as `R.string.remote_status_confirming`.
     *
     * It says what the user has to do, which is why it outranks every connection state: the connection to the
     * chosen remote exists only so that press can arrive, so "Verbunden" would be true and useless here.
     */
    const val CONFIRMING = "Tastendruck an der Remote wird erwartet"

    /**
     * Set up, but the system has not reported the remote as present yet — the normal idle state, not an error.
     * Same wording as `R.string.remote_status_out_of_range`.
     */
    const val OUT_OF_RANGE = "Nicht in Reichweite – verbindet sich automatisch"

    /**
     * After half a day the time of day alone no longer says which day a measurement or command belongs to, so
     * [timestamp] and [shortTimestamp] add the date from then on.
     */
    const val DATE_SHOWN_AFTER_MS: Long = 12L * 60 * 60 * 1000

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val SHORT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm:ss")

    private val SHORT_DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")

    fun connection(state: BleConnectionState): String = when (state) {
        BleConnectionState.Disconnected -> "Nicht verbunden"
        BleConnectionState.Connecting -> "Verbindung wird aufgebaut"
        BleConnectionState.DiscoveringServices -> "Dienste werden gesucht"
        BleConnectionState.EnablingNotifications -> "Benachrichtigungen werden aktiviert"
        BleConnectionState.Connected -> "Verbunden"
        is BleConnectionState.Reconnecting ->
            "Neuer Versuch ${state.attempt}/${state.maxAttempts} in ${seconds(state.delayMs)} s"
        is BleConnectionState.Failed -> "Fehler: ${failure(state.reason)}"
    }

    fun failure(reason: FailureReason): String = when (reason) {
        FailureReason.BluetoothAdapterMissing -> "kein Bluetooth-Adapter"
        FailureReason.BluetoothDisabled -> "Bluetooth ist aus"
        FailureReason.MissingPermission -> "Berechtigung fehlt"
        is FailureReason.GattError -> "GATT-Status ${reason.status} beim ${step(reason.step)}"
        is FailureReason.ConnectionLost -> "Verbindung verloren (Status ${reason.status})"
        is FailureReason.OperationNotStarted -> "${step(reason.step)} nicht gestartet"
        is FailureReason.Timeout -> "Zeitüberschreitung beim ${step(reason.step)}"
        is FailureReason.DeviceUnsuitable -> "Gerät passt nicht (${reason.missing} fehlt)"
    }

    fun step(step: GattStep): String = when (step) {
        GattStep.CONNECT -> "Verbinden"
        GattStep.DISCOVER_SERVICES -> "Dienste suchen"
        GattStep.ENABLE_NOTIFICATIONS -> "Aktivieren der Benachrichtigungen"
    }

    fun command(command: RemoteCommand): String = when (command) {
        RemoteCommand.PLAY_PAUSE -> "Play/Pause"
        RemoteCommand.NEXT -> "Nächster Titel"
        RemoteCommand.PREVIOUS -> "Vorheriger Titel"
        RemoteCommand.VOLUME_UP -> "Lauter"
        RemoteCommand.VOLUME_DOWN -> "Leiser"
        RemoteCommand.CALL -> "Anruf"
        RemoteCommand.WALKIE_TALKIE -> "Sprachassistent"
        RemoteCommand.TURN_LEFT -> "Blinker links"
        RemoteCommand.TURN_RIGHT -> "Blinker rechts"
        RemoteCommand.REAR_LIGHT -> "Rücklicht"
        RemoteCommand.UNKNOWN -> "Unbekannt"
    }

    /** Shown on the screen and, lower case, in the notification while no measurement exists yet. */
    const val BATTERY_UNKNOWN = "Unbekannt"

    /**
     * The battery measurement as one value, e.g. `"60 % (3,74 V)"`, or [BATTERY_UNKNOWN] without a measurement.
     *
     * The voltage is in brackets, not behind another [SEPARATOR]: in the notification [SEPARATOR] separates the
     * fields, so `"Akku: 80 % · 3,91 V · Letzter Befehl: …"` would read like four fields instead of three.
     *
     * Without a voltage — the case of a Battery Level 0x2A19 notification — only the percentage is shown.
     * The percentage is the value the remote reported, deliberately not converted (see
     * [de.werklade.cratoniremote.protocol.RemoteStatusFrame] on what is still open about its meaning).
     *
     * This is the one text the screen shares with the notification instead of using a string resource: the number
     * format has to be fixed to German regardless of the device locale, and that is only worth having if it is
     * unit tested in one place.
     */
    fun batteryValue(battery: RemoteBattery?): String = when {
        battery == null -> BATTERY_UNKNOWN
        battery.millivolts == null -> "${battery.percent} %"
        else -> "${battery.percent} % (${volts(battery.millivolts)} V)"
    }

    /**
     * [millivolts] as volts with two decimals and a German decimal comma, e.g. `3736` → `"3,74"`.
     *
     * [Locale.GERMANY] explicitly: with the default locale of the device the same value would read `3.74` on an
     * English phone, next to German text.
     */
    fun volts(millivolts: Int): String = String.format(Locale.GERMANY, "%.2f", millivolts / 1000.0)

    /** `"Akku: 60 % (3,74 V)"`, or `"Akku: unbekannt"` while no valid measurement has been received. */
    fun battery(battery: RemoteBattery?): String =
        if (battery == null) "Akku: unbekannt" else "Akku: ${batteryValue(battery)}"

    /** Wall clock time of [atEpochMs] as `HH:mm:ss`; [zone] is injectable so tests do not depend on the machine. */
    fun time(atEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(atEpochMs).atZone(zone).format(TIME_FORMAT)

    /**
     * [atEpochMs] as `HH:mm`, or as `dd.MM.yyyy, HH:mm` once [showsDate] holds; used for "gemessen um 18:46"
     * under the battery level.
     *
     * Without seconds, because the second of a five minute poll says nothing — but with the date, exactly like
     * [timestamp]: a measurement can easily be many hours old while it is shown. A connection may stand across
     * midnight, and the poll runs on [android.os.SystemClock.uptimeMillis] via `Handler.postDelayed`, which
     * stands still in deep sleep. "gemessen um 23:58" under a value from the night before would be misleading.
     */
    fun shortTimestamp(
        atEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = Instant.ofEpochMilli(atEpochMs).atZone(zone).format(
        if (showsDate(atEpochMs, nowEpochMs)) SHORT_DATE_TIME_FORMAT else SHORT_TIME_FORMAT,
    )

    /**
     * Whether [atEpochMs] is so long ago ([DATE_SHOWN_AFTER_MS]) that the time of day alone would be misleading.
     *
     * The last command survives the service being stopped, so without this a command from yesterday evening
     * reads like one from a few minutes ago.
     */
    fun showsDate(atEpochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        nowEpochMs - atEpochMs >= DATE_SHOWN_AFTER_MS

    /** [atEpochMs] as `HH:mm:ss`, or as `dd.MM.yyyy, HH:mm:ss` once [showsDate] holds. */
    fun timestamp(
        atEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = if (showsDate(atEpochMs, nowEpochMs)) {
        Instant.ofEpochMilli(atEpochMs).atZone(zone).format(DATE_TIME_FORMAT)
    } else {
        time(atEpochMs, zone)
    }

    fun lastCommand(
        last: LastCommand?,
        nowEpochMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = if (last == null) {
        "Letzter Befehl: $NONE"
    } else {
        "Letzter Befehl: ${command(last.command)} (${timestamp(last.atEpochMs, nowEpochMs, zone)})"
    }

    /**
     * One line for the foreground service notification: connection state, plus battery level and last command
     * once they are known, e.g.
     * `"Verbunden · Akku: 80 % (3,91 V) · Letzter Befehl: Nächster Titel (12:34:56)"`.
     *
     * While [RemoteState.waitingForBluetooth] the connection state is replaced by [WAITING_FOR_BLUETOOTH]:
     * `"Nicht verbunden"` or a leftover `"Fehler: Bluetooth ist aus"` would hide the only thing that matters,
     * namely that the service is waiting for the adapter and connects by itself afterwards.
     *
     * The state line comes from [remoteLine], the same distinction the screen makes. A notification only
     * exists while the foreground service runs and a remote is set up, so both flags are set here — the very
     * first notification is built in `RemoteConnectionService.startForegroundSafely()`, a moment before
     * [RemoteStatus] is told that the service started, and must not read as "not in range" because of that.
     */
    fun notification(
        state: RemoteState,
        nowEpochMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildList {
        add(remoteLine(remoteConfigured = true, state = state.copy(serviceRunning = true)))
        if (state.battery != null) add(battery(state.battery))
        if (state.lastCommand != null) add(lastCommand(state.lastCommand, nowEpochMs, zone))
    }.joinToString(SEPARATOR)

    /**
     * State of the remote in one line, for the notification and — with the same distinction, in string
     * resources — for `ui.RemoteScreen`.
     *
     * Without an association nothing is observed; with one but without a running service the system has not
     * reported the remote as present yet, which is the normal idle state ([OUT_OF_RANGE]) and not an error.
     *
     * A running confirmation ([CONFIRMING]) comes right after "not set up" and before everything else: it is
     * the one state in which the user has something to do, and it is short-lived.
     *
     * @param remoteConfigured whether an association exists (`CompanionDeviceManager.getMyAssociations()`)
     */
    fun remoteLine(remoteConfigured: Boolean, state: RemoteState): String = when {
        !remoteConfigured -> NOT_CONFIGURED
        state.pendingConfirmation != null -> CONFIRMING
        state.waitingForBluetooth -> WAITING_FOR_BLUETOOTH
        !state.serviceRunning && state.connection == BleConnectionState.Disconnected -> OUT_OF_RANGE
        else -> connection(state.connection)
    }

    /** Whole seconds of [millis], rounded down; used for the reconnect delay. */
    private fun seconds(millis: Long): Long = millis / 1000
}
