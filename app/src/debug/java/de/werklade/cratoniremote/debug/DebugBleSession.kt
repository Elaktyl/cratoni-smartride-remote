package de.werklade.cratoniremote.debug

import android.content.Context
import android.media.AudioManager
import android.util.Log
import de.werklade.cratoniremote.action.ActionDispatcher
import de.werklade.cratoniremote.action.AudioManagerMediaKeyExecutor
import de.werklade.cratoniremote.action.AudioManagerVolumeExecutor
import de.werklade.cratoniremote.action.TelecomCallExecutor
import de.werklade.cratoniremote.action.DispatchResult
import de.werklade.cratoniremote.ble.CratoniBleManager
import de.werklade.cratoniremote.ble.PacketListener
import de.werklade.cratoniremote.protocol.CratoniProtocolParser
import de.werklade.cratoniremote.protocol.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only, process-wide holder of exactly one [CratoniBleManager], used by [DebugBleReceiver].
 *
 * Wiring: FFF4 packet → [CratoniProtocolParser] → only [ParseResult.Command] → [ActionDispatcher]
 * (AudioManager implementations as in [DebugCommandReceiver]) → log. Connection state and battery level are
 * logged from the [kotlinx.coroutines.flow.StateFlow]s as well; StateFlow conflates, so very fast transitions may
 * be missing here — the manager itself logs every transition.
 *
 * Packets are handled on the manager's BLE thread. The manager, its thread and the logging coroutines live until
 * the process ends; [CratoniBleManager.close] is never called.
 *
 * Without a foreground service Android may freeze the app process in the background; for testing step 4 the app
 * must be open in the foreground.
 */
object DebugBleSession {

    private const val TAG = CratoniBleManager.TAG

    @Volatile
    private var manager: CratoniBleManager? = null

    fun manager(context: Context): CratoniBleManager =
        manager ?: synchronized(this) {
            manager ?: create(context.applicationContext).also { manager = it }
        }

    private fun create(appContext: Context): CratoniBleManager {
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val dispatcher = audioManager?.let {
            ActionDispatcher(
                AudioManagerMediaKeyExecutor(it),
                AudioManagerVolumeExecutor(it),
                TelecomCallExecutor(appContext, it),
                // Real helmet client (DebugHelmet), so the light buttons switch the helmet here as well.
                DebugHelmet.connectedExecutor(appContext),
                DebugAssistant.executor(appContext),
            )
        }
        if (dispatcher == null) Log.e(TAG, "Session: AudioManager unavailable, packets will not be dispatched")

        val bleManager = CratoniBleManager(appContext, PacketListener { packet -> onPacket(packet, dispatcher) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            bleManager.connectionState.collect { Log.i(TAG, "Session: connection state $it") }
        }
        scope.launch {
            bleManager.battery.collect { battery ->
                val text = battery?.let { "${it.percent} %, ${it.millivolts ?: "-"} mV at ${it.atEpochMs}" }
                Log.i(TAG, "Session: battery ${text ?: "unknown"}")
            }
        }
        Log.i(TAG, "Session: BLE manager created")
        return bleManager
    }

    private fun onPacket(packet: ByteArray, dispatcher: ActionDispatcher?) {
        when (val result = CratoniProtocolParser.parse(packet)) {
            is ParseResult.Invalid ->
                Log.w(TAG, "Session: invalid packet ${result.reason} [${result.rawHex}], not dispatched")
            is ParseResult.Command -> {
                Log.i(TAG, "Session: parsed ${result.command} [${result.rawHex}]")
                if (dispatcher == null) {
                    Log.e(TAG, "Session: AudioManager unavailable, not dispatched")
                    return
                }
                val dispatchResult = dispatcher.dispatch(result.command)
                if (dispatchResult is DispatchResult.Failed) {
                    Log.w(TAG, "Session: dispatch failed: ${dispatchResult.command}", dispatchResult.cause)
                } else {
                    Log.i(TAG, "Session: dispatch $dispatchResult")
                }
            }
        }
    }
}
