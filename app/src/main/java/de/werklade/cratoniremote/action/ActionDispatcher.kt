package de.werklade.cratoniremote.action

import de.werklade.cratoniremote.helmet.TurnSignal
import de.werklade.cratoniremote.protocol.RemoteCommand

/**
 * The commands that trigger an action; every other [RemoteCommand] is only displayed
 * (see docs/architektur.md §6).
 *
 * Single source of truth for "does this command do anything?": [ActionDispatcher.dispatch] calls an executor
 * exactly for these commands, the UI uses the same set for its wording. It is deliberately not called
 * "media commands" any more — [RemoteCommand.CALL] is no media command, but it does act.
 *
 * The `when` in [ActionDispatcher.dispatch] stays exhaustive over [RemoteCommand] on purpose — the compiler
 * check is worth more than a set lookup; `ActionDispatcherTest` keeps both in sync.
 *
 * Note on [RemoteCommand.CALL]: membership means "an executor is called", not "something always happens".
 * Pressed while no call is ringing or running, it yields [DispatchResult.Ignored] — see [CallExecutor]. The same
 * holds for the three light commands: without a connection to the helmet nothing is sent and the result is
 * [DispatchResult.Ignored] — see [LightExecutor] — and for [RemoteCommand.WALKIE_TALKIE] outside `MODE_NORMAL` (ringing, call,
 * VoIP/intercom app) or while a previous start is still running — see [AssistantExecutor].
 */
val ACTION_COMMANDS: Set<RemoteCommand> = setOf(
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
)

/**
 * Maps a [RemoteCommand] to its action (see docs/architektur.md §6).
 *
 * - [RemoteCommand.PLAY_PAUSE], [RemoteCommand.NEXT], [RemoteCommand.PREVIOUS] → [MediaKeyExecutor]
 * - [RemoteCommand.VOLUME_UP], [RemoteCommand.VOLUME_DOWN] → [VolumeExecutor]
 * - [RemoteCommand.CALL] → [CallExecutor]
 * - [RemoteCommand.TURN_LEFT], [RemoteCommand.TURN_RIGHT], [RemoteCommand.REAR_LIGHT] → [LightExecutor]
 * - [RemoteCommand.WALKIE_TALKIE] → [AssistantExecutor]
 * - all other commands → [DispatchResult.Ignored], no executor is called
 *
 * The commands of the first five lines are [ACTION_COMMANDS].
 *
 * An executor reports with `false` that there was nothing to do — [CallExecutor], [LightExecutor] and
 * [AssistantExecutor] can, the media executors always act; that case is [DispatchResult.Ignored] as well, because
 * a call button pressed without a call, a light button pressed without a helmet in reach and an assistant button
 * pressed during a call are not errors.
 *
 * [dispatch] never throws an [Exception]: exceptions of the executors are returned as
 * [DispatchResult.Failed]. Pure Kotlin without Android dependencies.
 */
class ActionDispatcher(
    private val mediaKeys: MediaKeyExecutor,
    private val volume: VolumeExecutor,
    private val call: CallExecutor,
    private val lights: LightExecutor,
    private val assistant: AssistantExecutor,
) {

    fun dispatch(command: RemoteCommand): DispatchResult {
        val action: (() -> Boolean)? = when (command) {
            RemoteCommand.PLAY_PAUSE -> acting(mediaKeys::playPause)
            RemoteCommand.NEXT -> acting(mediaKeys::next)
            RemoteCommand.PREVIOUS -> acting(mediaKeys::previous)
            RemoteCommand.VOLUME_UP -> acting(volume::raise)
            RemoteCommand.VOLUME_DOWN -> acting(volume::lower)
            RemoteCommand.CALL -> call::answerOrHangUp
            RemoteCommand.TURN_LEFT -> ({ lights.toggleTurnSignal(TurnSignal.LEFT) })
            RemoteCommand.TURN_RIGHT -> ({ lights.toggleTurnSignal(TurnSignal.RIGHT) })
            RemoteCommand.REAR_LIGHT -> lights::cycleRearLight
            RemoteCommand.WALKIE_TALKIE -> assistant::startAssistant
            RemoteCommand.UNKNOWN -> null
        }
        if (action == null) return DispatchResult.Ignored(command)

        return try {
            if (action()) DispatchResult.Executed(command) else DispatchResult.Ignored(command)
        } catch (e: Exception) {
            DispatchResult.Failed(command, e)
        }
    }

    /** Wraps an executor call that cannot report "nothing to do" — running it always counts as executed. */
    private fun acting(block: () -> Unit): () -> Boolean = {
        block()
        true
    }
}
