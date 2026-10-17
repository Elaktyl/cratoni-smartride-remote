package de.werklade.cratoniremote.action

import android.media.AudioManager

/**
 * [VolumeExecutor] via [AudioManager.adjustStreamVolume] with the system volume panel
 * ([AudioManager.FLAG_SHOW_UI]). The stream follows the current call state ([volumeStreamForAudioMode]), so
 * the remote's volume keys change the call volume during a call and the media volume otherwise. Media
 * affects local playback only, not Cast/Connect.
 *
 * No permission is involved: `adjustStreamVolume(int, int, int)` carries no `@RequiresPermission` in the
 * android-37.0 stubs — neither in `data/annotations.zip` (only an `IntDef` for the flags parameter) nor in
 * `android-stubs-src.jar`. That holds for [AudioManager.STREAM_VOICE_CALL] as well; the stream type
 * parameter is not annotated at all.
 *
 * A [SecurityException] from the system is not caught here; [ActionDispatcher] reports it as
 * [DispatchResult.Failed].
 */
class AudioManagerVolumeExecutor(private val audioManager: AudioManager) : VolumeExecutor {

    override fun raise() = adjust(AudioManager.ADJUST_RAISE)

    override fun lower() = adjust(AudioManager.ADJUST_LOWER)

    private fun adjust(direction: Int) {
        val stream = volumeStreamForAudioMode(audioManager.mode)
        audioManager.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
    }
}

/**
 * The stream the volume keys act on, derived from [AudioManager.getMode] — the same source
 * [callActionForAudioMode] uses to tell "call running" from "no call", so both keys agree on the state.
 *
 * [AudioManager.MODE_IN_CALL] (telephony) and [AudioManager.MODE_IN_COMMUNICATION] (VoIP) mean a call is
 * running, everything else — including [AudioManager.MODE_RINGTONE] and every mode a future release may add
 * — keeps the previous behaviour, [AudioManager.STREAM_MUSIC].
 *
 * Deliberately not `adjustSuggestedStreamVolume(direction, USE_DEFAULT_STREAM_TYPE, flags)`: that would let
 * the platform pick the stream, and while nothing is playing the pick can be the ring stream — a button
 * press would then silently turn down the ringer instead of the media volume.
 */
internal fun volumeStreamForAudioMode(mode: Int): Int = when (mode) {
    AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
    else -> AudioManager.STREAM_MUSIC
}
