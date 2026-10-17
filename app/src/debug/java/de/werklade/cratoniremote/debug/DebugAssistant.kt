package de.werklade.cratoniremote.debug

import android.content.Context
import android.media.AudioManager
import android.util.Log
import de.werklade.cratoniremote.action.MediaKeyAssistantExecutor
import de.werklade.cratoniremote.ble.CratoniBleManager

/**
 * Debug-only, process-wide holder of one [MediaKeyAssistantExecutor] for [DebugCommandReceiver] and
 * [DebugBleSession].
 *
 * One instance on purpose: [DebugCommandReceiver] builds a new dispatcher per broadcast, and with a new executor
 * each time its guard against a second press while a sequence runs would not hold across two quick broadcasts —
 * which is exactly what an adb test of that guard does. Like [DebugHelmet] it is never closed; it owns no thread,
 * and a sequence that is still running when the process ends simply dies with it.
 *
 * The service ([de.werklade.cratoniremote.service.RemoteConnectionService]) has its own executor, so a debug
 * trigger and a real button press at the same moment are not guarded against each other.
 */
object DebugAssistant {

    private const val TAG = CratoniBleManager.TAG

    @Volatile
    private var executor: MediaKeyAssistantExecutor? = null

    /**
     * The shared executor, built from the application context's [AudioManager] on first use — never from the
     * restricted context of a single broadcast, because the instance outlives it.
     */
    fun executor(context: Context): MediaKeyAssistantExecutor =
        executor ?: synchronized(this) {
            executor ?: create(context.applicationContext).also { executor = it }
        }

    private fun create(appContext: Context): MediaKeyAssistantExecutor {
        val audioManager = checkNotNull(appContext.getSystemService(AudioManager::class.java)) {
            "AudioManager unavailable"
        }
        Log.i(TAG, "DebugAssistant: executor created")
        return MediaKeyAssistantExecutor(audioManager)
    }
}
