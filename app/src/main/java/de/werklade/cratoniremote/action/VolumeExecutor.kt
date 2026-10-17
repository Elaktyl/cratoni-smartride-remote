package de.werklade.cratoniremote.action

/**
 * Media volume control, one step per call.
 *
 * Implementations may throw; [ActionDispatcher] reports exceptions as [DispatchResult.Failed].
 */
interface VolumeExecutor {
    fun raise()
    fun lower()
}
