package de.werklade.cratoniremote.service

import android.content.Context
import de.werklade.cratoniremote.R
import de.werklade.cratoniremote.data.RemoteState
import de.werklade.cratoniremote.data.RemoteStatusText
import de.werklade.cratoniremote.data.StatusText
import de.werklade.cratoniremote.data.StringLookup
import de.werklade.cratoniremote.data.TextSpec
import de.werklade.cratoniremote.data.resolve
import java.time.ZoneId
import java.util.Locale

/**
 * The one line of the foreground service notification, e.g.
 * `"Connected · Battery: 80% (3.91 V) · Last command: Next track (12:34:56)"`.
 *
 * It is the only place the user sees the connection state while the app is closed, and it is permanently
 * visible, so it follows the language of the app like everything else: every word comes from string resources
 * ([Context.remoteNotificationText]), while which words in which order is decided here
 * ([fields]) — without Android, so local unit tests cover it.
 */
object NotificationText {

    /** Separator between the fields of the line. */
    const val SEPARATOR = " · "

    /**
     * The fields of the line: the state of the remote, plus the battery level and the last command once they
     * are known. A field that has no value is left out instead of being filled with a placeholder — the line
     * has to stay short enough for one row in the status bar.
     *
     * The state comes from [StatusText.remoteLine], the same decision the screen makes, with two flags set
     * here: a notification only exists while the service runs and a remote is set up. The very first one is
     * built in [RemoteConnectionService.startForegroundSafely], a moment before
     * [de.werklade.cratoniremote.data.RemoteStatus] is told that the service started, and must not read as
     * "not in range" because of that.
     *
     * While [RemoteState.waitingForBluetooth] the state is replaced by `notification_waiting_for_bluetooth`:
     * "Disconnected", or a leftover "Error: Bluetooth is switched off", would hide the only thing that
     * matters, namely that the service waits for the adapter and connects by itself afterwards.
     *
     * @param locale the language the notification is shown in, for the numbers and times inside the fields
     */
    fun fields(
        state: RemoteState,
        nowEpochMs: Long,
        zone: ZoneId,
        locale: Locale,
    ): List<TextSpec> = buildList {
        add(
            StatusText.remoteLine(
                remoteConfigured = true,
                state = state.copy(serviceRunning = true),
                waitingForBluetooth = R.string.notification_waiting_for_bluetooth,
            ),
        )
        state.battery?.let { battery ->
            add(TextSpec(R.string.notification_battery, listOf(RemoteStatusText.batteryValue(battery, locale))))
        }
        state.lastCommand?.let { last ->
            add(
                TextSpec(
                    R.string.notification_last_command,
                    listOf(
                        StatusText.command(last.command),
                        RemoteStatusText.timestamp(last.atEpochMs, nowEpochMs, zone, locale),
                    ),
                ),
            )
        }
    }

    /** [fields], resolved through [lookup] and joined with [SEPARATOR] to the single line that is shown. */
    fun line(
        lookup: StringLookup,
        state: RemoteState,
        nowEpochMs: Long,
        zone: ZoneId,
        locale: Locale,
    ): String = fields(state, nowEpochMs, zone, locale).joinToString(SEPARATOR) { it.resolve(lookup) }
}

/**
 * [NotificationText.line] in the language of this context.
 *
 * Numbers and times are formatted for the locale the resources were resolved with, not for
 * [Locale.getDefault]: both are the language of the app, but the configuration is what the words in the same
 * line come from, so `"Akku: 80 % (3,91 V)"` can never end up with English numbers.
 */
fun Context.remoteNotificationText(
    state: RemoteState,
    nowEpochMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = NotificationText.line(
    // getString(id) without arguments on purpose: the vararg form runs String.format over the result, which
    // would stumble over a per cent sign in a translation that has no placeholder at all.
    lookup = { id, args -> if (args.isEmpty()) getString(id) else getString(id, *args) },
    state = state,
    nowEpochMs = nowEpochMs,
    zone = zone,
    locale = resources.configuration.locales[0],
)
