package de.werklade.cratoniremote.action

/**
 * Transport controls for the currently active media app.
 *
 * Implementations may throw; [ActionDispatcher] reports exceptions as [DispatchResult.Failed].
 */
interface MediaKeyExecutor {
    fun playPause()
    fun next()
    fun previous()
}
