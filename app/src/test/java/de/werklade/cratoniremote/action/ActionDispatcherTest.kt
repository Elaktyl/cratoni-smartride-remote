package de.werklade.cratoniremote.action

import de.werklade.cratoniremote.helmet.TurnSignal
import de.werklade.cratoniremote.protocol.RemoteCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionDispatcherTest {

    /** Shared call log of all fakes, so a single assertion covers "right call, exactly once, nothing else". */
    private val calls = mutableListOf<String>()

    /** When set, every executor call is recorded and then throws this exception. */
    private var failure: Exception? = null

    /**
     * What the fake [CallExecutor] reports: `true` means "a call was accepted or ended", `false` means
     * "nothing to do" — no call in progress, or `ANSWER_PHONE_CALLS` missing. Default `true`, so the
     * general tests below see the call button as an acting one.
     */
    private var callActed = true

    /**
     * What the fake [LightExecutor] reports: `false` means "no connection to the helmet", so nothing was sent.
     * Default `true`, so the general tests below see the three light buttons as acting ones.
     */
    private var lightsActed = true

    /**
     * What the fake [AssistantExecutor] reports: `false` means "nothing sent" — a call in progress or a previous
     * sequence still running. Default `true`, so the general tests below see the walkie-talkie button as acting.
     */
    private var assistantActed = true

    private val mediaKeys = object : MediaKeyExecutor {
        override fun playPause() = record("playPause")
        override fun next() = record("next")
        override fun previous() = record("previous")
    }

    private val volume = object : VolumeExecutor {
        override fun raise() = record("raise")
        override fun lower() = record("lower")
    }

    private val call = object : CallExecutor {
        override fun answerOrHangUp(): Boolean {
            record("answerOrHangUp")
            return callActed
        }
    }

    private val lights = object : LightExecutor {
        override fun toggleTurnSignal(side: TurnSignal): Boolean {
            record("toggleTurnSignal($side)")
            return lightsActed
        }

        override fun cycleRearLight(): Boolean {
            record("cycleRearLight")
            return lightsActed
        }
    }

    private val assistant = object : AssistantExecutor {
        override fun startAssistant(): Boolean {
            record("startAssistant")
            return assistantActed
        }
    }

    private val dispatcher = ActionDispatcher(mediaKeys, volume, call, lights, assistant)

    private fun record(call: String) {
        calls += call
        failure?.let { throw it }
    }

    // Executed

    @Test
    fun `PLAY_PAUSE calls playPause once`() {
        assertExecuted(RemoteCommand.PLAY_PAUSE, "playPause")
    }

    @Test
    fun `NEXT calls next once`() {
        assertExecuted(RemoteCommand.NEXT, "next")
    }

    @Test
    fun `PREVIOUS calls previous once`() {
        assertExecuted(RemoteCommand.PREVIOUS, "previous")
    }

    @Test
    fun `VOLUME_UP calls raise once`() {
        assertExecuted(RemoteCommand.VOLUME_UP, "raise")
    }

    @Test
    fun `VOLUME_DOWN calls lower once`() {
        assertExecuted(RemoteCommand.VOLUME_DOWN, "lower")
    }

    // CALL: the one command whose executor can report "nothing to do"

    @Test
    fun `CALL accepts a ringing call`() {
        callActed = true

        assertExecuted(RemoteCommand.CALL, "answerOrHangUp")
    }

    @Test
    fun `CALL without a call in progress is Ignored, not Failed`() {
        callActed = false

        val result = dispatcher.dispatch(RemoteCommand.CALL)

        assertEquals(DispatchResult.Ignored(RemoteCommand.CALL), result)
        assertEquals(listOf("answerOrHangUp"), calls)
    }

    /**
     * Accepting and hanging up are one command here — which of the two happens is the executor's decision
     * (see `callActionForAudioMode`). What the dispatcher owes is that every press reaches the executor and
     * is judged on its own answer: press one accepts, press two runs into the hang-up lock and reports
     * "nothing done". A dispatcher that remembered the first result would hide the second.
     */
    @Test
    fun `each CALL press is asked and judged on its own`() {
        callActed = true
        assertEquals(DispatchResult.Executed(RemoteCommand.CALL), dispatcher.dispatch(RemoteCommand.CALL))

        callActed = false
        assertEquals(DispatchResult.Ignored(RemoteCommand.CALL), dispatcher.dispatch(RemoteCommand.CALL))

        callActed = true
        assertEquals(DispatchResult.Executed(RemoteCommand.CALL), dispatcher.dispatch(RemoteCommand.CALL))

        assertEquals(List(3) { "answerOrHangUp" }, calls)
    }

    /**
     * [RemoteCommand.CALL], the three light commands and [RemoteCommand.WALKIE_TALKIE] are the action commands
     * whose executor may report "nothing done" — a missing `ANSWER_PHONE_CALLS`, no call, a press inside the
     * hang-up lock, no connection to the helmet, or an assistant start during a call or a running sequence. That
     * the executors really answer `false` in those cases instead of throwing is covered by
     * `TelecomCallExecutorTest`, `HelmetLightExecutorTest` and `MediaKeyAssistantExecutorTest`; here it is the
     * mapping that matters:
     * `false` becomes [DispatchResult.Ignored], while the media and volume executors return nothing at all and
     * therefore always count as [DispatchResult.Executed].
     */
    @Test
    fun `CALL, the light commands and WALKIE_TALKIE can report nothing done, the others always act`() {
        callActed = false
        lightsActed = false
        assistantActed = false

        for (command in ACTION_COMMANDS) {
            calls.clear()

            val result = dispatcher.dispatch(command)

            val expected = if (command in CAN_REPORT_NOTHING_DONE) {
                DispatchResult.Ignored(command)
            } else {
                DispatchResult.Executed(command)
            }
            assertEquals("result for $command", expected, result)
            assertEquals("calls for $command", listOf(EXECUTED.getValue(command)), calls)
        }
    }

    // Lights: the three buttons that switch the helmet (docs/architektur.md §13)

    @Test
    fun `TURN_LEFT toggles the left turn signal once`() {
        assertExecuted(RemoteCommand.TURN_LEFT, "toggleTurnSignal(LEFT)")
    }

    @Test
    fun `TURN_RIGHT toggles the right turn signal once`() {
        assertExecuted(RemoteCommand.TURN_RIGHT, "toggleTurnSignal(RIGHT)")
    }

    @Test
    fun `REAR_LIGHT cycles the rear light once`() {
        assertExecuted(RemoteCommand.REAR_LIGHT, "cycleRearLight")
    }

    /** Helmet off or out of range: the frame was not sent, which is no error and must stay silent. */
    @Test
    fun `a light command without a helmet connection is Ignored, not Failed`() {
        lightsActed = false

        assertEquals(DispatchResult.Ignored(RemoteCommand.TURN_LEFT), dispatcher.dispatch(RemoteCommand.TURN_LEFT))
        assertEquals(DispatchResult.Ignored(RemoteCommand.REAR_LIGHT), dispatcher.dispatch(RemoteCommand.REAR_LIGHT))
        assertEquals(listOf("toggleTurnSignal(LEFT)", "cycleRearLight"), calls)
    }

    /** The realistic failure of a light button: the helmet client throws instead of answering. */
    @Test
    fun `IllegalStateException from light executor yields Failed`() {
        val exception = IllegalStateException("socket gone")
        failure = exception

        val result = dispatcher.dispatch(RemoteCommand.TURN_RIGHT)

        assertFailed(result, RemoteCommand.TURN_RIGHT, exception)
        assertEquals(listOf("toggleTurnSignal(RIGHT)"), calls)
    }

    // WALKIE_TALKIE: starts the voice assistant (docs/architektur.md §12)

    @Test
    fun `WALKIE_TALKIE starts the assistant once`() {
        assertExecuted(RemoteCommand.WALKIE_TALKIE, "startAssistant")
    }

    /** During a call or while a sequence still runs nothing is sent, which is no error and must stay silent. */
    @Test
    fun `WALKIE_TALKIE that sends nothing is Ignored, not Failed`() {
        assistantActed = false

        val result = dispatcher.dispatch(RemoteCommand.WALKIE_TALKIE)

        assertEquals(DispatchResult.Ignored(RemoteCommand.WALKIE_TALKIE), result)
        assertEquals(listOf("startAssistant"), calls)
    }

    /** The user's hard requirement: the assistant hangs on the walkie-talkie button, the call button is untouched. */
    @Test
    fun `CALL never starts the assistant and WALKIE_TALKIE never touches the call`() {
        dispatcher.dispatch(RemoteCommand.CALL)
        dispatcher.dispatch(RemoteCommand.WALKIE_TALKIE)

        assertEquals(listOf("answerOrHangUp", "startAssistant"), calls)
    }

    @Test
    fun `IllegalStateException from assistant executor yields Failed`() {
        val exception = IllegalStateException("looper is exiting")
        failure = exception

        val result = dispatcher.dispatch(RemoteCommand.WALKIE_TALKIE)

        assertFailed(result, RemoteCommand.WALKIE_TALKIE, exception)
        assertEquals(listOf("startAssistant"), calls)
    }

    // Ignored

    @Test
    fun `UNKNOWN is ignored`() {
        assertIgnored(RemoteCommand.UNKNOWN)
    }

    @Test
    fun `ignored commands call no executor even if executors would throw`() {
        failure = RuntimeException("must not be reached")

        for (command in IGNORED) {
            assertEquals(DispatchResult.Ignored(command), dispatcher.dispatch(command))
        }
        assertEquals(emptyList<String>(), calls)
    }

    // Failed

    @Test
    fun `RuntimeException from media key executor yields Failed`() {
        val exception = RuntimeException("boom")
        failure = exception

        val result = dispatcher.dispatch(RemoteCommand.NEXT)

        assertFailed(result, RemoteCommand.NEXT, exception)
        assertEquals(listOf("next"), calls)
    }

    @Test
    fun `SecurityException from volume executor yields Failed`() {
        val exception = SecurityException("denied")
        failure = exception

        val result = dispatcher.dispatch(RemoteCommand.VOLUME_UP)

        assertFailed(result, RemoteCommand.VOLUME_UP, exception)
        assertEquals(listOf("raise"), calls)
    }

    /**
     * The realistic failure of the call button: the permission was revoked between the check and the call,
     * or the platform refuses it. It must not reach the caller as a crash.
     */
    @Test
    fun `SecurityException from call executor yields Failed`() {
        val exception = SecurityException("ANSWER_PHONE_CALLS denied")
        failure = exception

        val result = dispatcher.dispatch(RemoteCommand.CALL)

        assertFailed(result, RemoteCommand.CALL, exception)
        assertEquals(listOf("answerOrHangUp"), calls)
    }

    @Test
    fun `every executed command yields Failed when its executor throws`() {
        for (command in EXECUTED.keys) {
            val exception = IllegalStateException("failure for $command")
            failure = exception
            calls.clear()

            val result = dispatcher.dispatch(command)

            assertFailed(result, command, exception)
            assertEquals("calls for $command", listOf(EXECUTED.getValue(command)), calls)
        }
    }

    // Coverage of all enum values

    @Test
    fun `every RemoteCommand is classified as expected`() {
        for (command in RemoteCommand.entries) {
            calls.clear()

            val result = dispatcher.dispatch(command)

            val expectedCall = EXECUTED[command]
            if (expectedCall != null) {
                assertEquals(DispatchResult.Executed(command), result)
                assertEquals("calls for $command", listOf(expectedCall), calls)
            } else {
                assertTrue("$command must be listed as ignored", command in IGNORED)
                assertEquals(DispatchResult.Ignored(command), result)
                assertEquals("calls for $command", emptyList<String>(), calls)
            }
        }
        assertEquals(RemoteCommand.entries.toSet(), EXECUTED.keys + IGNORED)
    }

    /**
     * Binds the published set to the actual behaviour: the UI reads [ACTION_COMMANDS] to decide whether a
     * command does anything, so an executor must be called exactly for its members — and for no one else.
     *
     * Measured with executors that all act ([callActed], [lightsActed], [assistantActed] `true`); the members in
     * [CAN_REPORT_NOTHING_DONE] can still answer "nothing to do" at runtime, which the Ignored tests above cover.
     */
    @Test
    fun `dispatch executes exactly the commands in ACTION_COMMANDS`() {
        callActed = true
        lightsActed = true
        assistantActed = true

        for (command in RemoteCommand.entries) {
            calls.clear()

            val result = dispatcher.dispatch(command)

            val expected = if (command in ACTION_COMMANDS) {
                DispatchResult.Executed(command)
            } else {
                DispatchResult.Ignored(command)
            }
            assertEquals("result for $command", expected, result)
        }
    }

    @Test
    fun `ACTION_COMMANDS contains the ten documented commands`() {
        assertEquals(
            setOf(
                RemoteCommand.PLAY_PAUSE,
                RemoteCommand.NEXT,
                RemoteCommand.PREVIOUS,
                RemoteCommand.VOLUME_UP,
                RemoteCommand.VOLUME_DOWN,
                RemoteCommand.CALL,
                RemoteCommand.TURN_LEFT,
                RemoteCommand.TURN_RIGHT,
                RemoteCommand.REAR_LIGHT,
                RemoteCommand.WALKIE_TALKIE,
            ),
            ACTION_COMMANDS,
        )
    }

    private fun assertExecuted(command: RemoteCommand, expectedCall: String) {
        assertEquals(DispatchResult.Executed(command), dispatcher.dispatch(command))
        assertEquals(listOf(expectedCall), calls)
    }

    private fun assertIgnored(command: RemoteCommand) {
        assertEquals(DispatchResult.Ignored(command), dispatcher.dispatch(command))
        assertEquals(emptyList<String>(), calls)
    }

    private fun assertFailed(result: DispatchResult, command: RemoteCommand, cause: Exception) {
        assertTrue("expected Failed for $command but was $result", result is DispatchResult.Failed)
        result as DispatchResult.Failed
        assertEquals(command, result.command)
        assertSame(cause, result.cause)
    }

    private companion object {
        val EXECUTED = mapOf(
            RemoteCommand.PLAY_PAUSE to "playPause",
            RemoteCommand.NEXT to "next",
            RemoteCommand.PREVIOUS to "previous",
            RemoteCommand.VOLUME_UP to "raise",
            RemoteCommand.VOLUME_DOWN to "lower",
            RemoteCommand.CALL to "answerOrHangUp",
            RemoteCommand.TURN_LEFT to "toggleTurnSignal(LEFT)",
            RemoteCommand.TURN_RIGHT to "toggleTurnSignal(RIGHT)",
            RemoteCommand.REAR_LIGHT to "cycleRearLight",
            RemoteCommand.WALKIE_TALKIE to "startAssistant",
        )
        val IGNORED = setOf(
            RemoteCommand.UNKNOWN,
        )

        /** The action commands whose executor can answer "nothing to do" at runtime. */
        val CAN_REPORT_NOTHING_DONE = setOf(
            RemoteCommand.CALL,
            RemoteCommand.TURN_LEFT,
            RemoteCommand.TURN_RIGHT,
            RemoteCommand.REAR_LIGHT,
            RemoteCommand.WALKIE_TALKIE,
        )
    }
}
