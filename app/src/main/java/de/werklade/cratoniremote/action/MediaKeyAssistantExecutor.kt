package de.werklade.cratoniremote.action

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewConfiguration
import de.werklade.cratoniremote.ble.CratoniBleManager

/**
 * [AssistantExecutor] that sends a long press of the headset key ([KeyEvent.KEYCODE_HEADSETHOOK]) through
 * [AudioManager.dispatchMediaKeyEvent]; `MediaSessionService` then starts the hands-free voice search
 * (docs/architektur.md §12, verified on the S25 Ultra on 16.10.2026: works with the phone locked, sound through
 * the helmet, music continues afterwards, no accidental play/pause).
 *
 * ## Relies on undocumented system behaviour
 *
 * That a voice-key long press dispatched by an app reaches `MediaSessionService`'s voice handling is not part of
 * any public API contract; it follows from the AOSP implementation. A future Android or One UI update can change
 * it — then the button silently stops working (the log still shows the three events as sent).
 *
 * ## What the user sees
 *
 * - On the very first press Android asks which app should handle it (Google, Bixby, Alexa …). Choose Google
 *   (`voicesearch.handsfree.HandsFreeActivity`) with "Immer"; after that the choice is permanent.
 * - Sent only in [AudioManager.MODE_NORMAL]; in every other audio mode nothing at all is sent (logged; if the mode
 *   changes after the DOWN, only the canceled UP follows, see below). The
 *   system accepts the key from `SYSTEM_UID` only while a global-priority session (telephony) is active, so during
 *   a call it would not start the assistant anyway. Blocked therefore:
 *   - [AudioManager.MODE_RINGTONE]: a headset-key long press is the "reject" gesture of a telephony headset, and a
 *     voice assistant opening over a ringing call helps nobody.
 *   - [AudioManager.MODE_IN_CALL] (telephony) and [AudioManager.MODE_IN_COMMUNICATION] (VoIP). The latter
 *     includes intercom and other VoIP apps: the button is deliberately dead while they hold that mode, because
 *     the assistant would otherwise take over their microphone.
 *   - Every other mode, including those this code does not know (`MODE_CALL_SCREENING`, `MODE_CALL_REDIRECT`,
 *     `MODE_COMMUNICATION_REDIRECT` and whatever later Android versions add): an unknown mode is treated as "not
 *     safe to take the microphone", not as "no call". This is stricter than [callActionForAudioMode], which the
 *     call and volume keys use and which maps unknown modes to [CallAction.NONE].
 *
 * ## The sequence (unchanged from the experiment that was verified on the device)
 *
 * All three events carry the same `downTime = t0` ([SystemClock.uptimeMillis]), key code
 * [KeyEvent.KEYCODE_HEADSETHOOK], meta state 0, device [KeyCharacterMap.VIRTUAL_KEYBOARD], scan code 0:
 * 1. `ACTION_DOWN`, repeat 0, flags 0, `eventTime = t0`
 * 2. after [ViewConfiguration.getLongPressTimeout] (400 ms on the test device): `ACTION_DOWN`, repeat 1,
 *    [KeyEvent.FLAG_LONG_PRESS]
 * 3. [RELEASE_DELAY_MS] later: `ACTION_UP`, repeat 0, flags 0
 *
 * If sending fails in the middle, an `ACTION_UP` is still sent, then with [KeyEvent.FLAG_CANCELED], so the system
 * is never left with a pressed key; the canceled flag keeps that release from being replayed as a short press
 * (play/pause). The same happens in [close], and when the audio mode is no longer [AudioManager.MODE_NORMAL]
 * right before step 2 (checked again there, because a call can start ringing during the long press timeout and
 * the long press would then count as "reject"): the long press is not sent, only the canceled `ACTION_UP`.
 *
 * ## Threads
 *
 * [startAssistant] is called on the BLE thread. It only reads the audio mode, claims the executor and posts the
 * first step, then returns — it never waits. All three events are sent from the steps on the main looper
 * ([Handler.postDelayed]), one after the other, so they can never overtake each other and [close] (called from
 * `Service.onDestroy` on the main thread) cannot interleave with a step either.
 *
 * Only one sequence at a time: from [startAssistant] until its `ACTION_UP` has been sent, further calls return
 * `false` (logged), so two sequences never interleave and no `ACTION_DOWN` is left without its `ACTION_UP`.
 *
 * No permission: `dispatchMediaKeyEvent` declares none (same as [AudioManagerMediaKeyExecutor]).
 */
class MediaKeyAssistantExecutor internal constructor(
    private val sendKey: (VoiceKeyEvent) -> Unit,
    private val modeProvider: () -> Int,
    private val scheduler: StepScheduler,
    private val uptime: () -> Long,
    private val longPressTimeoutMs: () -> Long,
    private val log: (Int, String) -> Unit,
) : AssistantExecutor {

    /**
     * The constructor for the app: events go to [AudioManager.dispatchMediaKeyEvent], steps run on the main looper,
     * the long press timeout is read from [ViewConfiguration] on every press (the user can change it in the
     * accessibility settings), timestamps come from [SystemClock.uptimeMillis] like those of real key events.
     */
    constructor(audioManager: AudioManager) : this(
        sendKey = { audioManager.dispatchMediaKeyEvent(it.toKeyEvent()) },
        modeProvider = { audioManager.mode },
        scheduler = HandlerStepScheduler(Handler(Looper.getMainLooper())),
        uptime = SystemClock::uptimeMillis,
        longPressTimeoutMs = { ViewConfiguration.getLongPressTimeout().toLong() },
        log = { priority, message -> Log.println(priority, TAG, message) },
    )

    private val lock = Any()

    /** The sequence from its acceptance in [startAssistant] until its `ACTION_UP`; guarded by [lock]. */
    private var running: Sequence? = null

    /** Guarded by [lock]. */
    private var closed = false

    override fun startAssistant(): Boolean {
        val mode = modeProvider()
        if (mode != AudioManager.MODE_NORMAL) {
            log(Log.INFO, "Assistant: assistant not started, not in normal audio mode (audio mode $mode)")
            return false
        }
        val sequence = Sequence()
        synchronized(lock) {
            if (closed) {
                log(Log.WARN, "Assistant: executor closed, assistant not started")
                return false
            }
            if (running != null) {
                log(Log.INFO, "Assistant: previous sequence still running, press ignored")
                return false
            }
            running = sequence
        }
        try {
            scheduler.schedule(0) { pressDown(sequence) }
        } catch (e: Exception) {
            // Nothing has been sent yet, so there is no key to release.
            finish(sequence)
            throw e
        }
        return true
    }

    /**
     * Cancels a running sequence and sends its `ACTION_UP` (with [KeyEvent.FLAG_CANCELED]) if its `ACTION_DOWN` was
     * already sent; afterwards [startAssistant] returns `false`. Idempotent.
     *
     * Must be called on the thread the steps run on — the main thread, as `Service.onDestroy` is.
     */
    fun close() {
        val sequence = synchronized(lock) {
            if (closed) return
            closed = true
            running
        }
        scheduler.cancelAll()
        if (sequence != null) {
            log(Log.INFO, "Assistant: closed during a sequence, releasing the key")
            release(sequence, canceled = true)
        }
    }

    /** Step 1, on the scheduler's thread. */
    private fun pressDown(sequence: Sequence) {
        if (!isRunning(sequence)) return
        try {
            val downTime = uptime()
            sequence.downTime = downTime
            val timeout = longPressTimeoutMs()
            // Set before sending: if dispatching throws, the system may still have received the event.
            sequence.downSent = true
            sendKey(VoiceKeyEvent(KeyEvent.ACTION_DOWN, downTime, downTime, repeatCount = 0, flags = 0))
            log(Log.INFO, "Assistant: DOWN sent, long press in $timeout ms")
            scheduler.schedule(timeout) { pressLong(sequence) }
        } catch (e: Exception) {
            log(Log.WARN, "Assistant: sequence aborted at DOWN: $e")
            release(sequence, canceled = true)
        }
    }

    /**
     * Step 2, on the scheduler's thread. Reads the audio mode again: if it left [AudioManager.MODE_NORMAL] since
     * [startAssistant] (e.g. a call started ringing), no long press is sent and the key is released canceled.
     */
    private fun pressLong(sequence: Sequence) {
        if (!isRunning(sequence)) return
        try {
            val mode = modeProvider()
            if (mode != AudioManager.MODE_NORMAL) {
                log(Log.INFO, "Assistant: audio mode changed during the sequence (audio mode $mode), releasing the key")
                release(sequence, canceled = true)
                return
            }
            val eventTime = uptime()
            sendKey(
                VoiceKeyEvent(
                    KeyEvent.ACTION_DOWN,
                    sequence.downTime,
                    eventTime,
                    repeatCount = 1,
                    flags = KeyEvent.FLAG_LONG_PRESS,
                ),
            )
            log(Log.INFO, "Assistant: long press sent (+${eventTime - sequence.downTime} ms)")
            scheduler.schedule(RELEASE_DELAY_MS) { release(sequence, canceled = false) }
        } catch (e: Exception) {
            log(Log.WARN, "Assistant: sequence aborted at long press: $e")
            release(sequence, canceled = true)
        }
    }

    /**
     * Step 3, on the scheduler's thread, and the way out of every failure: sends the `ACTION_UP` if a DOWN went out
     * and frees the executor in any case. Never throws — it runs on the main looper, where an exception would crash
     * the app.
     */
    private fun release(sequence: Sequence, canceled: Boolean) {
        if (!isRunning(sequence)) return
        try {
            if (sequence.downSent) {
                val eventTime = uptime()
                val flags = if (canceled) KeyEvent.FLAG_CANCELED else 0
                sendKey(VoiceKeyEvent(KeyEvent.ACTION_UP, sequence.downTime, eventTime, repeatCount = 0, flags = flags))
                log(
                    Log.INFO,
                    "Assistant: UP sent${if (canceled) " (canceled)" else ""}, " +
                        "sequence done after ${eventTime - sequence.downTime} ms",
                )
            }
        } catch (e: Exception) {
            log(Log.WARN, "Assistant: UP failed: $e")
        } finally {
            finish(sequence)
        }
    }

    private fun isRunning(sequence: Sequence): Boolean = synchronized(lock) { running === sequence }

    private fun finish(sequence: Sequence) {
        synchronized(lock) {
            if (running === sequence) running = null
        }
    }

    /**
     * One press of the button. Identity tells a stale step from the current sequence. [downTime] and [downSent] are
     * only touched on the scheduler's thread.
     */
    private class Sequence {
        var downTime: Long = 0
        var downSent: Boolean = false
    }

    internal companion object {
        private const val TAG = CratoniBleManager.TAG

        /** Time between the long press and the release, as in the verified experiment. */
        internal const val RELEASE_DELAY_MS = 100L
    }
}

/**
 * The values of one [KeyEvent] of the sequence, as plain data: [KeyEvent] cannot be built outside a device, so
 * the tests compare these instead. [toKeyEvent] turns them into the real event.
 */
internal data class VoiceKeyEvent(
    val action: Int,
    val downTime: Long,
    val eventTime: Long,
    val repeatCount: Int,
    val flags: Int,
    val keyCode: Int = KeyEvent.KEYCODE_HEADSETHOOK,
) {
    /** Same constructor and the same meta state, device id and scan code as the verified experiment. */
    fun toKeyEvent(): KeyEvent = KeyEvent(
        downTime,
        eventTime,
        action,
        keyCode,
        repeatCount,
        /* metaState = */ 0,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        /* scancode = */ 0,
        flags,
    )
}

/** Delayed execution behind an interface, so [MediaKeyAssistantExecutor] can be tested without a looper. */
internal interface StepScheduler {

    /** Runs [step] after [delayMs] milliseconds on the scheduler's thread; never on the calling thread directly. */
    fun schedule(delayMs: Long, step: () -> Unit)

    /** Drops every step that has not run yet. */
    fun cancelAll()
}

/**
 * [StepScheduler] on a [Handler]. A private token marks this executor's callbacks, so [cancelAll] removes them and
 * nothing else that runs on the same looper.
 */
private class HandlerStepScheduler(private val handler: Handler) : StepScheduler {

    private val token = Any()

    override fun schedule(delayMs: Long, step: () -> Unit) {
        check(handler.postDelayed({ step() }, token, delayMs)) { "looper is exiting, step not scheduled" }
    }

    override fun cancelAll() = handler.removeCallbacksAndMessages(token)
}
