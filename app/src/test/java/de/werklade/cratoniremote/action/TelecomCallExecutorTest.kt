package de.werklade.cratoniremote.action

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [TelecomCallExecutor] without an Android runtime.
 *
 * The executor takes its audio mode, its clock, its permission check and the two `TelecomManager` calls as
 * injected functions ([PlatformCalls]), so everything except the platform calls themselves is covered here:
 * the ringing/running decision, the permission guard, the lock after an accept and the caught
 * [SecurityException]. The `AudioManager.MODE_*` values are compile-time constants (`public static final
 * int`, verified with `javap -constants` against android-37.0) and are inlined by the compiler.
 *
 * What stays untested here — because it needs a device and a real call: that `acceptRingingCall()` really
 * connects (it returns `void`, see the executor's KDoc) and that `endCall()` really hangs up.
 */
class TelecomCallExecutorTest {

    /** Ordered log of everything the executor asks or calls, so one assertion covers order and count. */
    private val calls = mutableListOf<String>()
    private val logged = mutableListOf<Pair<Int, String>>()

    private var mode = AudioManager.MODE_NORMAL
    private var now = 10_000L
    private var permission = true
    private var endCallResult = true

    /** When set, [PlatformCalls] records the call and then throws — the revoked-permission race. */
    private var platformFailure: SecurityException? = null

    private var companionQueries = 0

    private val platform = object : PlatformCalls {
        override fun acceptRingingCall() {
            calls += "acceptRingingCall"
            platformFailure?.let { throw it }
        }

        override fun endCall(): Boolean {
            calls += "endCall"
            platformFailure?.let { throw it }
            return endCallResult
        }

        override fun hasManageOngoingCallsPermission(): Boolean {
            companionQueries++
            return false
        }
    }

    private fun executor(platformCalls: PlatformCalls? = platform) = TelecomCallExecutor(
        platformCalls = platformCalls,
        permissionGranted = {
            calls += "permissionCheck"
            permission
        },
        modeProvider = {
            calls += "mode"
            mode
        },
        clock = { now },
        log = { priority, message -> logged += priority to message },
    )

    // The decision: ringing or running call

    @Test
    fun `ringing means answer`() {
        assertEquals(CallAction.ANSWER, callActionForAudioMode(AudioManager.MODE_RINGTONE))
    }

    @Test
    fun `telephony call in progress means hang up`() {
        assertEquals(CallAction.HANG_UP, callActionForAudioMode(AudioManager.MODE_IN_CALL))
    }

    @Test
    fun `VoIP call in progress means hang up`() {
        assertEquals(CallAction.HANG_UP, callActionForAudioMode(AudioManager.MODE_IN_COMMUNICATION))
    }

    @Test
    fun `no call means nothing to do`() {
        assertEquals(CallAction.NONE, callActionForAudioMode(AudioManager.MODE_NORMAL))
    }

    /**
     * Everything not listed must end up as [CallAction.NONE]. Accepting or ending a call while the phone is
     * screening, redirecting or in any mode added by a future release would be the worst possible guess.
     */
    @Test
    fun `every other mode means nothing to do`() {
        val others = listOf(
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT,
            AudioManager.MODE_CURRENT,
            AudioManager.MODE_INVALID,
            Int.MAX_VALUE,
        )

        for (mode in others) {
            assertEquals("mode $mode", CallAction.NONE, callActionForAudioMode(mode))
        }
    }

    // The two actions, and the order they are decided in

    @Test
    fun `a ringing call is accepted, and the mode is read before anything is called`() {
        mode = AudioManager.MODE_RINGTONE

        assertTrue(executor().answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck", "acceptRingingCall"), calls)
    }

    @Test
    fun `a running call is ended, and the mode is read before anything is called`() {
        mode = AudioManager.MODE_IN_CALL

        assertTrue(executor().answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck", "endCall"), calls)
    }

    /** An emergency call the platform refuses to end: reported as "nothing done", not as a success. */
    @Test
    fun `a refused endCall is reported as nothing done`() {
        mode = AudioManager.MODE_IN_CALL
        endCallResult = false

        assertFalse(executor().answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck", "endCall"), calls)
    }

    @Test
    fun `without a call nothing is called and no permission is checked`() {
        mode = AudioManager.MODE_NORMAL

        assertFalse(executor().answerOrHangUp())
        assertEquals(listOf("mode"), calls)
    }

    @Test
    fun `without a TelecomManager nothing happens at all`() {
        mode = AudioManager.MODE_RINGTONE

        assertFalse(executor(platformCalls = null).answerOrHangUp())
        assertEquals(emptyList<String>(), calls)
    }

    // The permission guard

    @Test
    fun `without the permission a ringing call is left alone`() {
        mode = AudioManager.MODE_RINGTONE
        permission = false

        assertFalse(executor().answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck"), calls)
    }

    @Test
    fun `without the permission a running call is left alone`() {
        mode = AudioManager.MODE_IN_CALL
        permission = false

        assertFalse(executor().answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck"), calls)
    }

    /** A denied permission is not a malfunction: it is logged, so the log tells it from "no call". */
    @Test
    fun `a denied permission is logged`() {
        mode = AudioManager.MODE_RINGTONE
        permission = false

        executor().answerOrHangUp()

        assertTrue("logged: $logged", logged.any { it.second.contains("ANSWER_PHONE_CALLS") })
    }

    // The revoked-permission race

    @Test
    fun `a SecurityException from acceptRingingCall is caught`() {
        mode = AudioManager.MODE_RINGTONE
        platformFailure = SecurityException("ANSWER_PHONE_CALLS revoked")

        assertFalse(executor().answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck", "acceptRingingCall"), calls)
    }

    @Test
    fun `a SecurityException from endCall is caught`() {
        mode = AudioManager.MODE_IN_CALL
        platformFailure = SecurityException("ANSWER_PHONE_CALLS revoked")

        assertFalse(executor().answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck", "endCall"), calls)
    }

    /** A failed accept must not arm the lock — otherwise a call that is up stays up for three seconds. */
    @Test
    fun `a failed accept does not lock hanging up`() {
        val executor = executor()
        mode = AudioManager.MODE_RINGTONE
        platformFailure = SecurityException("revoked")
        executor.answerOrHangUp()

        platformFailure = null
        mode = AudioManager.MODE_IN_CALL
        now += 100

        assertTrue(executor.answerOrHangUp())
        assertTrue("calls: $calls", calls.contains("endCall"))
    }

    // The lock after an accept

    @Test
    fun `a second press right after an accept does not hang up`() {
        val executor = executor()
        acceptAt(executor, 10_000L)
        calls.clear()

        // The audio mode has reached "in call" by now, which is exactly the trap: without the lock this
        // press would end the call that was just accepted.
        mode = AudioManager.MODE_IN_CALL
        now = 10_400L

        assertFalse(executor.answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck"), calls)
        assertTrue("logged: $logged", logged.any { it.second.contains("hang up ignored") })
    }

    @Test
    fun `the lock ends after three seconds`() {
        val executor = executor()
        acceptAt(executor, 10_000L)
        mode = AudioManager.MODE_IN_CALL

        now = 10_000L + TelecomCallExecutor.HANG_UP_LOCK_MS - 1
        calls.clear()
        assertFalse("still locked one millisecond early", executor.answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck"), calls)

        now = 10_000L + TelecomCallExecutor.HANG_UP_LOCK_MS
        calls.clear()
        assertTrue("lock must be over", executor.answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck", "endCall"), calls)
    }

    @Test
    fun `the lock is three seconds long`() {
        assertEquals(3_000L, TelecomCallExecutor.HANG_UP_LOCK_MS)
    }

    /** The lock blocks hanging up only; a call ringing again right after must still be accepted. */
    @Test
    fun `accepting stays possible while hanging up is locked`() {
        val executor = executor()
        acceptAt(executor, 10_000L)
        calls.clear()

        mode = AudioManager.MODE_RINGTONE
        now = 10_100L

        assertTrue(executor.answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck", "acceptRingingCall"), calls)
    }

    /** Without an accept in this process there is nothing to lock — the first press may hang up. */
    @Test
    fun `hanging up works without a preceding accept`() {
        mode = AudioManager.MODE_IN_CALL
        now = 500L

        assertTrue(executor().answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck", "endCall"), calls)
    }

    @Test
    fun `the next call after a hang up is locked again`() {
        val executor = executor()
        acceptAt(executor, 10_000L)
        mode = AudioManager.MODE_IN_CALL
        now = 20_000L
        assertTrue(executor.answerOrHangUp())

        acceptAt(executor, 30_000L)
        mode = AudioManager.MODE_IN_CALL
        now = 30_500L
        calls.clear()

        assertFalse(executor.answerOrHangUp())
        assertEquals(listOf("mode", "permissionCheck"), calls)
    }

    // The one-off companion log

    @Test
    fun `the companion permission is queried once per executor`() {
        val executor = executor()
        mode = AudioManager.MODE_NORMAL

        executor.answerOrHangUp()
        executor.answerOrHangUp()

        assertEquals(1, companionQueries)
        assertTrue(
            "logged: $logged",
            logged.any { it.second.contains("hasManageOngoingCallsPermission()=false") },
        )
    }

    private fun acceptAt(executor: TelecomCallExecutor, at: Long) {
        mode = AudioManager.MODE_RINGTONE
        now = at
        assertTrue("accept at $at", executor.answerOrHangUp())
    }
}
