package de.werklade.cratoniremote.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import de.werklade.cratoniremote.action.ActionDispatcher
import de.werklade.cratoniremote.action.AudioManagerMediaKeyExecutor
import de.werklade.cratoniremote.action.AudioManagerVolumeExecutor
import de.werklade.cratoniremote.action.DispatchResult
import de.werklade.cratoniremote.action.TelecomCallExecutor
import de.werklade.cratoniremote.protocol.CratoniProtocolParser
import de.werklade.cratoniremote.protocol.ParseResult
import de.werklade.cratoniremote.protocol.RemoteCommand

/**
 * Debug-only adb trigger for the action layer, bypassing BLE. Protected by `android.permission.DUMP`.
 *
 * Exactly one String extra:
 * - [EXTRA_COMMAND]: name of a [RemoteCommand], e.g. `NEXT`, `TURN_LEFT`, `REAR_LIGHT` or `WALKIE_TALKIE`
 * - [EXTRA_PACKET]: hex bytes, spaces optional, e.g. `0F530265`; runs through [CratoniProtocolParser]
 *
 * `command=WALKIE_TALKIE` (or `packet=0F57036A`) runs the production path of the walkie-talkie button: the
 * voice-key long press of [de.werklade.cratoniremote.action.MediaKeyAssistantExecutor], from one process-wide
 * instance ([DebugAssistant]), so a second broadcast within about half a second is ignored like a double press.
 * The broadcast ends right away; the sequence finishes on the main looper.
 *
 * Invalid input is only logged (tag [TAG]). Examples:
 * ```
 * adb shell am broadcast --user 0 -n de.werklade.cratoniremote/.debug.DebugCommandReceiver --es command NEXT
 * adb shell am broadcast --user 0 -n de.werklade.cratoniremote/.debug.DebugCommandReceiver --es command WALKIE_TALKIE
 * adb shell am broadcast --user 0 -n de.werklade.cratoniremote/.debug.DebugCommandReceiver --es packet 0F530265
 * adb shell "am broadcast --user 0 -n de.werklade.cratoniremote/.debug.DebugCommandReceiver --es packet '0F 53 02 65'"
 * adb logcat -s CratoniRemote
 * ```
 * If the app has not been launched since installation (stopped state), launch it once or add
 * `--include-stopped-packages`.
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val commandName = intent.getStringExtra(EXTRA_COMMAND)
        val packet = intent.getStringExtra(EXTRA_PACKET)
        Log.i(TAG, "Input: $EXTRA_COMMAND=$commandName, $EXTRA_PACKET=$packet")

        val command = when {
            commandName != null && packet != null -> reject("both extras given, expected exactly one")
            commandName != null -> RemoteCommand.entries.firstOrNull { it.name == commandName }
                ?: reject("unknown command name '$commandName'")
            packet != null -> commandFromPacket(packet)
            else -> reject("missing String extra '$EXTRA_COMMAND' or '$EXTRA_PACKET'")
        } ?: return

        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            Log.e(TAG, "AudioManager unavailable, not dispatched")
            return
        }
        val dispatcher = ActionDispatcher(
            AudioManagerMediaKeyExecutor(audioManager),
            AudioManagerVolumeExecutor(audioManager),
            // Same executor as in RemoteConnectionService, so `--es command CALL` exercises the real path
            // (audio mode → accept/end, permission check, logging) without a key press on the remote.
            TelecomCallExecutor(context.applicationContext, audioManager),
            // Real helmet client (DebugHelmet): `--es command TURN_LEFT` really switches the helmet, which is how
            // the light path can be checked without a key press on the remote.
            DebugHelmet.connectedExecutor(context.applicationContext),
            DebugAssistant.executor(context.applicationContext),
        )
        val result = dispatcher.dispatch(command)
        if (result is DispatchResult.Failed) {
            Log.w(TAG, "Dispatch failed: ${result.command}", result.cause)
        } else {
            Log.i(TAG, "Dispatch: $result")
        }
    }

    private fun commandFromPacket(packet: String): RemoteCommand? {
        val bytes = decodeHex(packet) ?: return reject("malformed hex '$packet'")
        return when (val result = CratoniProtocolParser.parse(bytes)) {
            is ParseResult.Command -> {
                Log.i(TAG, "Parsed: ${result.command} [${result.rawHex}]")
                result.command
            }
            is ParseResult.Invalid -> {
                Log.w(TAG, "Parsed: invalid ${result.reason} [${result.rawHex}], not dispatched")
                null
            }
        }
    }

    private fun reject(reason: String): RemoteCommand? {
        Log.w(TAG, "Rejected: $reason")
        return null
    }

    /** Decodes ASCII hex digits, ignoring whitespace; `null` for odd digit count or non-hex characters. */
    private fun decodeHex(text: String): ByteArray? {
        val digits = text.filterNot { it.isWhitespace() }
        if (digits.length % 2 != 0) return null
        val bytes = ByteArray(digits.length / 2)
        for (i in bytes.indices) {
            val high = hexValue(digits[2 * i]) ?: return null
            val low = hexValue(digits[2 * i + 1]) ?: return null
            bytes[i] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private fun hexValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> null
    }

    companion object {
        const val TAG = "CratoniRemote"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_PACKET = "packet"
    }
}
