package de.werklade.cratoniremote.action

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the stream choice of [AudioManagerVolumeExecutor], the part that is free of Android.
 *
 * The `AudioManager.MODE_*` and `STREAM_*` values are compile-time constants (`public static final int`,
 * verified with `javap -constants` against android-37.0), so they are inlined here and no Android runtime is
 * involved. The [AudioManager.adjustStreamVolume] call itself needs a device.
 */
class AudioManagerVolumeExecutorTest {

    @Test
    fun `telephony call in progress uses the voice call stream`() {
        assertEquals(AudioManager.STREAM_VOICE_CALL, volumeStreamForAudioMode(AudioManager.MODE_IN_CALL))
    }

    @Test
    fun `VoIP call in progress uses the voice call stream`() {
        assertEquals(
            AudioManager.STREAM_VOICE_CALL,
            volumeStreamForAudioMode(AudioManager.MODE_IN_COMMUNICATION),
        )
    }

    @Test
    fun `no call uses the music stream`() {
        assertEquals(AudioManager.STREAM_MUSIC, volumeStreamForAudioMode(AudioManager.MODE_NORMAL))
    }

    /**
     * While the phone is ringing the call is not up yet: the voice call stream has no effect then, and
     * turning the ringer down is not what a volume key press should do either — so it stays media.
     */
    @Test
    fun `ringing still uses the music stream`() {
        assertEquals(AudioManager.STREAM_MUSIC, volumeStreamForAudioMode(AudioManager.MODE_RINGTONE))
    }

    /** Everything not listed keeps the previous behaviour, including modes a future release may add. */
    @Test
    fun `every other mode uses the music stream`() {
        val others = listOf(
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT,
            AudioManager.MODE_CURRENT,
            AudioManager.MODE_INVALID,
            Int.MIN_VALUE,
            Int.MAX_VALUE,
        )

        for (mode in others) {
            assertEquals("mode $mode", AudioManager.STREAM_MUSIC, volumeStreamForAudioMode(mode))
        }
    }

    /**
     * Binds the two functions that read the same audio mode: whenever the call button would hang up, the
     * volume keys must be on the voice call stream — otherwise one key would see a call and the other would
     * not.
     */
    @Test
    fun `voice call stream exactly while the call button would hang up`() {
        val modes = listOf(
            AudioManager.MODE_NORMAL,
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT,
            AudioManager.MODE_INVALID,
        )

        for (mode in modes) {
            val expected = if (callActionForAudioMode(mode) == CallAction.HANG_UP) {
                AudioManager.STREAM_VOICE_CALL
            } else {
                AudioManager.STREAM_MUSIC
            }
            assertEquals("mode $mode", expected, volumeStreamForAudioMode(mode))
        }
    }
}
