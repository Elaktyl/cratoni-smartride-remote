package de.werklade.cratoniremote.debug

import android.content.Context
import android.util.Log
import de.werklade.cratoniremote.action.HelmetLightExecutor
import de.werklade.cratoniremote.ble.CratoniBleManager
import de.werklade.cratoniremote.data.RemoteStatus
import de.werklade.cratoniremote.helmet.HelmetSppClient

/**
 * Debug-only, process-wide holder of one [HelmetSppClient], so the adb triggers ([DebugCommandReceiver]) and the
 * debug BLE session ([DebugBleSession]) switch the real helmet through the real path instead of a stub.
 *
 * One client for the whole process on purpose: a new client per broadcast would open a second RFCOMM socket to
 * the same helmet and leave a thread behind every time. Like [DebugBleSession] it is never closed — the process
 * ends instead; that is acceptable for a debug tool and would not be in the service.
 *
 * [connectedExecutor] starts a connection attempt if none stands. It returns at once, so the very first press
 * after a cold start can still find the connection unfinished and report "nothing done" ([HelmetLightExecutor]);
 * the second press then works. In the real app the service has connected the helmet long before the first press.
 */
object DebugHelmet {

    private const val TAG = CratoniBleManager.TAG

    @Volatile
    private var client: HelmetSppClient? = null

    @Volatile
    private var executor: HelmetLightExecutor? = null

    fun connectedExecutor(context: Context): HelmetLightExecutor {
        val lights = synchronized(this) {
            executor ?: create(context.applicationContext).also { executor = it }
        }
        client?.connect()
        return lights
    }

    private fun create(appContext: Context): HelmetLightExecutor {
        val created = HelmetSppClient(appContext)
        client = created
        Log.i(TAG, "DebugHelmet: SPP client created")
        return HelmetLightExecutor(created) { lights -> RemoteStatus.onHelmetLights(lights) }
    }
}
