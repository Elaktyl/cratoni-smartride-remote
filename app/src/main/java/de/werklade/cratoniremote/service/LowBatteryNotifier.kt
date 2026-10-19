package de.werklade.cratoniremote.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import de.werklade.cratoniremote.R
import de.werklade.cratoniremote.ble.CratoniBleManager
import de.werklade.cratoniremote.ble.RemoteBattery
import de.werklade.cratoniremote.data.RemoteStatusText
import de.werklade.cratoniremote.ui.MainActivity

/**
 * Warns once per threshold ([LowBatteryWarning]) when the battery of the remote runs low.
 *
 * Own notification channel, audible ([NotificationManager.IMPORTANCE_DEFAULT]): the channel of the running
 * connection stays silent ([NotificationManager.IMPORTANCE_LOW]) on purpose — an ongoing notification that beeps
 * every time its text changes would be unbearable — and a warning that is never noticed is pointless. The two are
 * therefore separate channels the user can configure independently.
 *
 * The warning is a normal, swipeable notification with [Notification.Builder.setAutoCancel]: it is a one-off
 * message, not a state. Tapping it opens [MainActivity], where the measurement and its time are shown.
 *
 * The last warned threshold is persisted in [SharedPreferences], because the service is started and destroyed
 * often in normal use (companion device presence events); in process memory it would be lost every time and the
 * user would get the same warning on every connection.
 *
 * Threading: [onBattery] does file I/O (the first [SharedPreferences] access reads the XML file, writes go
 * through [SharedPreferences.Editor.apply], which only writes asynchronously) and is therefore called from the
 * battery collector of [RemoteConnectionService] on [kotlinx.coroutines.Dispatchers.IO] — never on the BLE
 * thread, which must stay free for the FFF4 button notifications (measured 4–25 ms from packet to action), and
 * never on the main thread. The mutable state lives in the preferences, not in this object, so there is nothing
 * to synchronise beyond that single collector.
 *
 * Without `POST_NOTIFICATIONS` nothing happens but a log line — no exception, and no new permission: the app
 * already declares it for the foreground service notification and asks for it in the UI.
 */
class LowBatteryNotifier(context: Context) {

    private val appContext: Context = context.applicationContext

    private val notificationManager: NotificationManager? =
        appContext.getSystemService(NotificationManager::class.java)

    /** Opened on first use, i.e. on the first measurement, not while the service is being created. */
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Audible warning channel, separate from the silent channel of the running connection.
     *
     * Called again on every language change (`RemoteConnectionService.refreshLocalisedText`), like the channel
     * of the connection: name and description come from string resources and stand in the system settings.
     * Creating a channel that exists only updates those (see
     * `RemoteConnectionService.createNotificationChannel` for the documented contract); what the user set on
     * the channel is kept.
     */
    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.low_battery_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = appContext.getString(R.string.low_battery_channel_description)
            setShowBadge(true)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    /**
     * Called with every measurement, and with `null` when the connection ends — then nothing happens: the stored
     * threshold has to survive exactly that.
     *
     * A value that climbed clearly above the last warned threshold arms it again ([LowBatteryWarning.isRearmed])
     * and is stored as such right away, so the charge is not forgotten if the process dies afterwards.
     */
    fun onBattery(battery: RemoteBattery?) {
        val measurement = battery ?: return
        val stored = lastWarnedLevel()
        val armed = if (stored != null && LowBatteryWarning.isRearmed(stored, measurement.percent)) {
            Log.i(TAG, "Battery warning: ${measurement.percent} % charged past $stored %, threshold armed again")
            storeLastWarnedLevel(LowBatteryWarning.NOTHING_WARNED)
            null
        } else {
            stored
        }
        val level = LowBatteryWarning.shouldWarn(armed, measurement.percent) ?: return
        if (!warn(level, measurement)) return
        storeLastWarnedLevel(level)
    }

    private fun lastWarnedLevel(): Int? =
        prefs.getInt(KEY_LAST_WARNED_LEVEL, LowBatteryWarning.NOTHING_WARNED)
            .takeIf { it != LowBatteryWarning.NOTHING_WARNED }

    private fun storeLastWarnedLevel(level: Int) {
        // apply() instead of commit(): the value is written asynchronously, the caller is not blocked, and a
        // repeated warning after a process kill in exactly that moment would be a harmless duplicate.
        prefs.edit().putInt(KEY_LAST_WARNED_LEVEL, level).apply()
    }

    /**
     * Posts the warning. `false` if it could not be posted — then the threshold is not stored, so the next
     * measurement tries again once the permission is there.
     */
    private fun warn(level: Int, battery: RemoteBattery): Boolean {
        if (appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Battery warning for $level % not shown: POST_NOTIFICATIONS not granted")
            return false
        }
        val manager = notificationManager
        if (manager == null) {
            Log.w(TAG, "Battery warning for $level % not shown: no NotificationManager")
            return false
        }
        // The measurement is formatted for the language the resources around it were resolved with, not for
        // Locale.getDefault() — the same rule the service notification follows ([remoteNotificationText]), so
        // "Akku bei 20 %" can never end up with an English number next to German words.
        val text = appContext.getString(
            R.string.low_battery_text,
            RemoteStatusText.batteryValue(battery, appContext.resources.configuration.locales[0]),
        )
        Log.i(TAG, "Battery warning at threshold $level %: '$text'")
        manager.notify(NOTIFICATION_ID, build(text))
        return true
    }

    private fun build(text: String): Notification = Notification.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_remote_notification)
        .setContentTitle(appContext.getString(R.string.low_battery_title))
        .setContentText(text)
        .setAutoCancel(true)
        .setContentIntent(contentIntent())
        .build()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        REQUEST_CODE,
        Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = CratoniBleManager.TAG

        /** Own channel, so the silent channel of the running connection stays silent. */
        const val CHANNEL_ID = "remote_battery_warning"

        /** Not [RemoteConnectionService]'s ID 1: the warning must not replace the foreground notification. */
        const val NOTIFICATION_ID = 2

        /** Own request code, so the [PendingIntent] of the warning cannot overwrite the one of the service. */
        private const val REQUEST_CODE = 1

        const val PREFS_NAME = "remote_battery"

        /** Threshold of the last warning, [LowBatteryWarning.NOTHING_WARNED] if there was none. */
        const val KEY_LAST_WARNED_LEVEL = "last_warned_level"
    }
}
