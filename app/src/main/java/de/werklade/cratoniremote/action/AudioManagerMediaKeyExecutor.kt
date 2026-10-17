package de.werklade.cratoniremote.action

import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * [MediaKeyExecutor] via [AudioManager.dispatchMediaKeyEvent]: sends `ACTION_DOWN`, then `ACTION_UP`
 * of the media key; the system routes it to the active media session. The SDK declares no
 * permission requirement for this API.
 */
class AudioManagerMediaKeyExecutor(private val audioManager: AudioManager) : MediaKeyExecutor {

    override fun playPause() = sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    override fun next() = sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)

    override fun previous() = sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun sendKey(keyCode: Int) {
        val downTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0),
        )
    }
}
