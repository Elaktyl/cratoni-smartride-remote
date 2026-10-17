package de.werklade.cratoniremote.action

import android.media.AudioManager
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [MediaKeyAssistantExecutor] without an Android runtime.
 *
 * Events, audio mode, clock, long press timeout, log and the delayed steps are injected: a virtual clock ([now])
 * drives both the timestamps and a fake [StepScheduler], so the spacing of the three events is checked exactly
 * and without waiting. The `KeyEvent.*` and `AudioManager.MODE_*` values are compile-time constants and are
 * inlined by the compiler, like in `TelecomCallExecutorTest`.
 *
 * What stays untested here — because it needs a device: that the system really starts the voice assistant from
 * this sequence ([VoiceKeyEvent.toKeyEvent] and the real `dispatchMediaKeyEvent` are not run).
 */
class MediaKeyAssistantExecutorTest {

    /** Every event the executor tried to send, in order — including one whose sending then threw. */
    private val sent = mutableListOf<VoiceKeyEvent>()
    private val logged = mutableListOf<Pair<Int, String>>()

    private var mode = AudioManager.MODE_NORMAL
    private var now = START
    private var longPressTimeout = LONG_PRESS_TIMEOUT

    /** When it returns `true` for an event, sending that event throws after it was recorded. */
    private var failWhen: (VoiceKeyEvent) -> Boolean = { false }

    private var cancelCalls = 0

    /** Pending steps with their due time on [now]'s scale; [advanceBy] runs them in due order. */
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()

    private val scheduler = object : StepScheduler {
        override fun schedule(delayMs: Long, step: () -> Unit) {
            pending += (now + delayMs) to step
        }

        override fun cancelAll() {
            cancelCalls++
            pending.clear()
        }
    }

    private val executor = MediaKeyAssistantExecutor(
        sendKey = { event ->
            sent += event
            if (failWhen(event)) throw IllegalStateException("dispatch failed for $event")
        },
        modeProvider = { mode },
        scheduler = scheduler,
        uptime = { now },
        longPressTimeoutMs = { longPressTimeout },
        log = { priority, message -> logged += priority to message },
    )

    /** Moves the virtual clock forward by [ms], running every step that becomes due on the way, in due order. */
    private fun advanceBy(ms: Long) {
        val target = now + ms
        while (true) {
            val next = pending.filter { it.first <= target }.minByOrNull { it.first } ?: break
            pending.remove(next)
            now = next.first
            next.second()
        }
        now = target
    }

    /** Runs the whole sequence: far more than the long press timeout plus the release delay. */
    private fun runToEnd() = advanceBy(10_000)

    private fun down(downTime: Long) = VoiceKeyEvent(KeyEvent.ACTION_DOWN, downTime, downTime, 0, 0)

    private fun longPress(downTime: Long, eventTime: Long) =
        VoiceKeyEvent(KeyEvent.ACTION_DOWN, downTime, eventTime, 1, KeyEvent.FLAG_LONG_PRESS)

    private fun up(downTime: Long, eventTime: Long, flags: Int = 0) =
        VoiceKeyEvent(KeyEvent.ACTION_UP, downTime, eventTime, 0, flags)

    // The sequence

    @Test
    fun `startAssistant returns at once and sends nothing on the calling thread`() {
        assertTrue(executor.startAssistant())

        assertEquals(emptyList<VoiceKeyEvent>(), sent)
        assertEquals("the first step is posted without delay", listOf(START), pending.map { it.first })
    }

    @Test
    fun `sends DOWN, the long press after the timeout and UP 100 ms later, all with the same downTime`() {
        executor.startAssistant()

        runToEnd()

        val longPressAt = START + LONG_PRESS_TIMEOUT
        val upAt = longPressAt + MediaKeyAssistantExecutor.RELEASE_DELAY_MS
        assertEquals(listOf(down(START), longPress(START, longPressAt), up(START, upAt)), sent)
    }

    @Test
    fun `every event is a HEADSETHOOK key with exactly the flags and repeat counts of the experiment`() {
        executor.startAssistant()
        runToEnd()

        assertEquals(List(3) { KeyEvent.KEYCODE_HEADSETHOOK }, sent.map { it.keyCode })
        assertEquals(
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP),
            sent.map { it.action },
        )
        assertEquals(listOf(0, 1, 0), sent.map { it.repeatCount })
        assertEquals(listOf(0, KeyEvent.FLAG_LONG_PRESS, 0), sent.map { it.flags })
        assertEquals(setOf(START), sent.map { it.downTime }.toSet())
    }

    @Test
    fun `nothing but the DOWN is sent before the long press timeout has passed`() {
        executor.startAssistant()

        advanceBy(LONG_PRESS_TIMEOUT - 1)
        assertEquals(listOf(down(START)), sent)

        advanceBy(1)
        assertEquals(listOf(down(START), longPress(START, START + LONG_PRESS_TIMEOUT)), sent)

        advanceBy(MediaKeyAssistantExecutor.RELEASE_DELAY_MS - 1)
        assertEquals("UP not yet", 2, sent.size)

        advanceBy(1)
        assertEquals(3, sent.size)
    }

    /** The timeout is a user setting (accessibility, "touch & hold delay"); it is read per press, not cached. */
    @Test
    fun `the long press follows the current long press timeout`() {
        longPressTimeout = 1_000

        executor.startAssistant()
        runToEnd()

        assertEquals(longPress(START, START + 1_000), sent[1])
        assertEquals(up(START, START + 1_000 + MediaKeyAssistantExecutor.RELEASE_DELAY_MS), sent[2])
    }

    // Double press

    @Test
    fun `a second press while the sequence runs is ignored and logged`() {
        assertTrue(executor.startAssistant())
        advanceBy(200)

        assertFalse(executor.startAssistant())

        runToEnd()
        assertEquals("one sequence, not two interleaved", 3, sent.size)
        assertTrue(logged.any { it.second.contains("press ignored") })
    }

    @Test
    fun `a second press before the first DOWN went out is ignored as well`() {
        assertTrue(executor.startAssistant())
        assertFalse(executor.startAssistant())

        runToEnd()
        assertEquals(3, sent.size)
    }

    @Test
    fun `a press right before the UP is still ignored`() {
        executor.startAssistant()
        advanceBy(LONG_PRESS_TIMEOUT + MediaKeyAssistantExecutor.RELEASE_DELAY_MS - 1)

        assertFalse(executor.startAssistant())
    }

    @Test
    fun `ready again once the UP has been sent`() {
        executor.startAssistant()
        runToEnd()
        val secondStart = now

        assertTrue(executor.startAssistant())
        runToEnd()

        assertEquals(6, sent.size)
        assertEquals(down(secondStart), sent[3])
        assertEquals(setOf(secondStart), sent.drop(3).map { it.downTime }.toSet())
    }

    // During a call

    @Test
    fun `nothing is sent during a telephony call`() {
        assertNothingSentInMode(AudioManager.MODE_IN_CALL)
    }

    @Test
    fun `nothing is sent during a VoIP call`() {
        assertNothingSentInMode(AudioManager.MODE_IN_COMMUNICATION)
    }

    /** Ringing counts as a call, as for the call button ([callActionForAudioMode]). */
    @Test
    fun `nothing is sent while a call is ringing`() {
        assertNothingSentInMode(AudioManager.MODE_RINGTONE)
    }

    // Only in the normal audio mode: modes the call heuristic does not know are blocked as well

    @Test
    fun `nothing is sent in call screening mode`() {
        assertEquals("constant as documented", 4, AudioManager.MODE_CALL_SCREENING)
        assertNothingSentInMode(AudioManager.MODE_CALL_SCREENING)
    }

    @Test
    fun `nothing is sent in communication redirect mode`() {
        assertEquals("constant as documented", 6, AudioManager.MODE_COMMUNICATION_REDIRECT)
        assertNothingSentInMode(AudioManager.MODE_COMMUNICATION_REDIRECT)
    }

    @Test
    fun `after the call the next press starts the assistant again`() {
        mode = AudioManager.MODE_IN_CALL
        assertFalse(executor.startAssistant())

        mode = AudioManager.MODE_NORMAL
        assertTrue(executor.startAssistant())
        runToEnd()
        assertEquals(3, sent.size)
    }

    /**
     * A call that starts ringing during the long press timeout: the long press would be the "reject" gesture, so
     * only a canceled UP follows the DOWN.
     */
    @Test
    fun `a call ringing before the long press releases the key canceled and sends no long press`() {
        assertTrue(executor.startAssistant())
        advanceBy(200)
        assertEquals(listOf(down(START)), sent)

        mode = AudioManager.MODE_RINGTONE
        runToEnd()

        val releasedAt = START + LONG_PRESS_TIMEOUT
        assertEquals(listOf(down(START), up(START, releasedAt, KeyEvent.FLAG_CANCELED)), sent)
        assertFalse(sent.any { (it.flags and KeyEvent.FLAG_LONG_PRESS) != 0 })
        assertEquals(emptyList<Pair<Long, () -> Unit>>(), pending)
        assertTrue(
            logged.any {
                it.second.contains("audio mode changed during the sequence (audio mode ${AudioManager.MODE_RINGTONE})")
            },
        )

        // Freed: once the ringing is over, the next press runs the full sequence again.
        mode = AudioManager.MODE_NORMAL
        val secondStart = now
        assertTrue(executor.startAssistant())
        runToEnd()
        val longPressAt = secondStart + LONG_PRESS_TIMEOUT
        assertEquals(
            listOf(
                down(secondStart),
                longPress(secondStart, longPressAt),
                up(secondStart, longPressAt + MediaKeyAssistantExecutor.RELEASE_DELAY_MS),
            ),
            sent.drop(2),
        )
    }

    // Failures: never a DOWN without an UP

    @Test
    fun `an exception at the long press still sends a canceled UP and frees the executor`() {
        failWhen = { it.repeatCount == 1 }
        executor.startAssistant()

        runToEnd()

        val failedAt = START + LONG_PRESS_TIMEOUT
        assertEquals(
            listOf(down(START), longPress(START, failedAt), up(START, failedAt, KeyEvent.FLAG_CANCELED)),
            sent,
        )
        assertTrue("ready again", executor.startAssistant())
    }

    /** The system may have received the DOWN although dispatching threw, so it is released as well. */
    @Test
    fun `an exception at the DOWN still sends a canceled UP and sends no long press`() {
        failWhen = { it.action == KeyEvent.ACTION_DOWN && it.repeatCount == 0 }
        executor.startAssistant()

        runToEnd()

        assertEquals(listOf(down(START), up(START, START, KeyEvent.FLAG_CANCELED)), sent)
        assertEquals(emptyList<Pair<Long, () -> Unit>>(), pending)
        assertTrue("ready again", executor.startAssistant())
    }

    @Test
    fun `an exception at the UP is logged and frees the executor`() {
        failWhen = { it.action == KeyEvent.ACTION_UP }
        executor.startAssistant()

        runToEnd()

        assertEquals(3, sent.size)
        assertTrue(logged.any { it.second.contains("UP failed") })
        assertTrue("ready again", executor.startAssistant())
    }

    @Test
    fun `an exception from the scheduler on start reaches the caller and leaves the executor free`() {
        val failing = MediaKeyAssistantExecutor(
            sendKey = { sent += it },
            modeProvider = { mode },
            scheduler = object : StepScheduler {
                override fun schedule(delayMs: Long, step: () -> Unit) = throw IllegalStateException("exiting")
                override fun cancelAll() = Unit
            },
            uptime = { now },
            longPressTimeoutMs = { longPressTimeout },
            log = { priority, message -> logged += priority to message },
        )

        val thrown = runCatching { failing.startAssistant() }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("nothing sent, so nothing to release", emptyList<VoiceKeyEvent>(), sent)
        assertTrue("not stuck as running", runCatching { failing.startAssistant() }.isFailure)
        assertFalse(logged.any { it.second.contains("press ignored") })
    }

    // close()

    @Test
    fun `close in the middle of a sequence sends a canceled UP and drops the pending steps`() {
        executor.startAssistant()
        advanceBy(200)

        executor.close()

        assertEquals(listOf(down(START), up(START, START + 200, KeyEvent.FLAG_CANCELED)), sent)
        assertEquals(1, cancelCalls)
        runToEnd()
        assertEquals("no long press after close", 2, sent.size)
    }

    @Test
    fun `close between the long press and the UP sends exactly one canceled UP and nothing after it`() {
        executor.startAssistant()
        val longPressAt = START + LONG_PRESS_TIMEOUT
        advanceBy(LONG_PRESS_TIMEOUT + 50)
        assertEquals(listOf(down(START), longPress(START, longPressAt)), sent)

        executor.close()

        val closedAt = longPressAt + 50
        assertEquals(
            listOf(down(START), longPress(START, longPressAt), up(START, closedAt, KeyEvent.FLAG_CANCELED)),
            sent,
        )
        assertEquals("the scheduled regular UP is dropped", emptyList<Pair<Long, () -> Unit>>(), pending)
        runToEnd()
        assertEquals("no second UP after close", 3, sent.size)

        // The sequence is no longer held; the executor refuses because it is closed, not because it is busy.
        assertFalse(executor.startAssistant())
        assertTrue(logged.any { it.second.contains("executor closed") })
        assertFalse(logged.any { it.second.contains("press ignored") })
        runToEnd()
        assertEquals(3, sent.size)
    }

    @Test
    fun `close before the DOWN went out sends nothing`() {
        executor.startAssistant()

        executor.close()
        runToEnd()

        assertEquals(emptyList<VoiceKeyEvent>(), sent)
    }

    @Test
    fun `after close nothing starts any more, and close is idempotent`() {
        executor.close()
        executor.close()

        assertFalse(executor.startAssistant())
        runToEnd()
        assertEquals(emptyList<VoiceKeyEvent>(), sent)
        assertEquals(1, cancelCalls)
    }

    private fun assertNothingSentInMode(blockedMode: Int) {
        mode = blockedMode

        assertFalse(executor.startAssistant())

        runToEnd()
        assertEquals(emptyList<VoiceKeyEvent>(), sent)
        assertEquals(emptyList<Pair<Long, () -> Unit>>(), pending)
        assertTrue(logged.any { it.second.contains("not in normal audio mode (audio mode $blockedMode)") })
    }

    private companion object {
        const val START = 50_000L
        const val LONG_PRESS_TIMEOUT = 400L
    }
}
