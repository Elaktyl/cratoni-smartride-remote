package de.werklade.cratoniremote.debug

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.werklade.cratoniremote.ble.BleLog
import de.werklade.cratoniremote.ble.CratoniBleManager
import de.werklade.cratoniremote.ble.RemoteBattery
import de.werklade.cratoniremote.service.LowBatteryNotifier
import de.werklade.cratoniremote.service.LowBatteryWarning
import java.util.Locale

/**
 * Debug-only adb trigger for [CratoniBleManager] through [DebugBleSession]. Protected by `android.permission.DUMP`.
 *
 * String extras:
 * - [EXTRA_BLE] = `connect` with [EXTRA_MAC] (required, case-insensitive, e.g. `A4:C1:38:1B:2C:3D`). The device is
 *   obtained via `BluetoothAdapter.getRemoteLeDevice(mac, BluetoothDevice.ADDRESS_TYPE_PUBLIC)` — assumption from
 *   docs/architektur.md §4 that the remote uses a public address.
 * - [EXTRA_BLE] = `disconnect`
 * - [EXTRA_BLE] = `battery` with [EXTRA_PERCENT] (required int, `0..100`) and optionally [EXTRA_MILLIVOLTS]:
 *   feeds a made-up measurement to [LowBatteryNotifier], so the low battery warning can be seen without
 *   discharging the remote. Uses the same stored threshold as the service, i.e. a repeated call stays quiet.
 * - [EXTRA_BLE] = `battery-reset`: forgets the stored threshold, so the next `battery` call warns again.
 *
 * Invalid input is only logged (tag `CratoniRemote`). `BLUETOOTH_CONNECT` must already be granted; step 4 has no UI
 * to request it, otherwise the state ends in `Failed(MissingPermission)`.
 *
 * Without a foreground service Android may freeze the app process in the background; for testing step 4 the app
 * must be open in the foreground.
 *
 * Examples:
 * ```
 * adb shell am broadcast --user 0 -n de.werklade.cratoniremote/.debug.DebugBleReceiver --es ble connect --es mac A4:C1:38:1B:2C:3D
 * adb shell am broadcast --user 0 -n de.werklade.cratoniremote/.debug.DebugBleReceiver --es ble disconnect
 * adb shell am broadcast --user 0 -n de.werklade.cratoniremote/.debug.DebugBleReceiver --es ble battery --ei percent 20 --ei millivolts 3680
 * adb shell am broadcast --user 0 -n de.werklade.cratoniremote/.debug.DebugBleReceiver --es ble battery-reset
 * adb logcat -s CratoniRemote
 * ```
 */
class DebugBleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_BLE)
        val mac = intent.getStringExtra(EXTRA_MAC)
        // The MAC is masked like everywhere else (see BleLog), even though it was passed in by the tester.
        Log.i(TAG, "BLE input: $EXTRA_BLE=$action, $EXTRA_MAC=${BleLog.mac(mac?.trim()?.uppercase(Locale.ROOT))}")

        when (action) {
            ACTION_CONNECT -> connect(context, mac)
            ACTION_DISCONNECT -> DebugBleSession.manager(context).disconnect()
            ACTION_BATTERY -> simulateBattery(
                context,
                intent.getIntExtra(EXTRA_PERCENT, NO_PERCENT),
                intent.getIntExtra(EXTRA_MILLIVOLTS, NO_MILLIVOLTS).takeIf { it != NO_MILLIVOLTS },
            )
            ACTION_BATTERY_RESET -> resetBatteryWarning(context)
            null -> Log.w(TAG, "BLE rejected: missing String extra '$EXTRA_BLE'")
            else -> Log.w(TAG, "BLE rejected: unknown '$EXTRA_BLE' value '$action'")
        }
    }

    /**
     * Feeds a made-up measurement to [LowBatteryNotifier] — the only way to see the warning without discharging
     * the remote. The channel is created here as well, because the service may not be running.
     *
     * Runs on its own thread: [LowBatteryNotifier.onBattery] touches [android.content.SharedPreferences], and a
     * receiver runs on the main thread.
     */
    private fun simulateBattery(context: Context, percent: Int, millivolts: Int?) {
        if (percent !in 0..100) {
            Log.w(TAG, "BLE rejected: '$ACTION_BATTERY' requires int extra '$EXTRA_PERCENT' in 0..100")
            return
        }
        val appContext = context.applicationContext
        Thread {
            val notifier = LowBatteryNotifier(appContext)
            notifier.createChannel()
            Log.i(TAG, "BLE: simulated battery $percent %, ${millivolts ?: "-"} mV")
            notifier.onBattery(RemoteBattery(percent, millivolts, System.currentTimeMillis()))
        }.start()
    }

    /** Forgets the last warned threshold, so the next simulated value warns again. */
    private fun resetBatteryWarning(context: Context) {
        val appContext = context.applicationContext
        Thread {
            appContext.getSharedPreferences(LowBatteryNotifier.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(LowBatteryNotifier.KEY_LAST_WARNED_LEVEL, LowBatteryWarning.NOTHING_WARNED)
                .apply()
            Log.i(TAG, "BLE: stored battery warning threshold cleared")
        }.start()
    }

    private fun connect(context: Context, mac: String?) {
        if (mac == null) {
            Log.w(TAG, "BLE rejected: '$ACTION_CONNECT' requires String extra '$EXTRA_MAC'")
            return
        }
        // checkBluetoothAddress only accepts upper case.
        val address = mac.trim().uppercase(Locale.ROOT)
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            Log.w(TAG, "BLE rejected: invalid MAC '${BleLog.mac(address)}'")
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Log.e(TAG, "BLE rejected: no Bluetooth adapter")
            return
        }
        val device = try {
            adapter.getRemoteLeDevice(address, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "BLE rejected: getRemoteLeDevice refused '${BleLog.mac(address)}'", e)
            return
        }
        DebugBleSession.manager(context).connect(device)
    }

    companion object {
        const val TAG = CratoniBleManager.TAG
        const val EXTRA_BLE = "ble"
        const val EXTRA_MAC = "mac"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_MILLIVOLTS = "millivolts"
        const val ACTION_CONNECT = "connect"
        const val ACTION_DISCONNECT = "disconnect"
        const val ACTION_BATTERY = "battery"
        const val ACTION_BATTERY_RESET = "battery-reset"

        /** Outside `0..100`, so a missing percentage is rejected instead of warning about 0 %. */
        private const val NO_PERCENT = -1

        /** No voltage given; then the warning shows the percentage only. */
        private const val NO_MILLIVOLTS = -1
    }
}
