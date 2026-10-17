package de.werklade.cratoniremote.action

/**
 * The remote's walkie-talkie button: starts the voice assistant, so commands like "call X" or "navigate home"
 * work without the wake word (docs/architektur.md §12, `0F 57 03 6A`).
 *
 * Implementations may throw; [ActionDispatcher] reports exceptions as [DispatchResult.Failed].
 *
 * @see MediaKeyAssistantExecutor
 */
interface AssistantExecutor {

    /**
     * Starts the voice assistant. Returns at once; the actual start may happen asynchronously.
     *
     * @return `true` if the start was triggered, `false` if there was nothing to do — the audio mode is not
     *   `MODE_NORMAL` (ringing, call, VoIP/intercom app, unknown modes) or a previous start is still in progress. [ActionDispatcher] turns `false` into [DispatchResult.Ignored].
     */
    fun startAssistant(): Boolean
}
