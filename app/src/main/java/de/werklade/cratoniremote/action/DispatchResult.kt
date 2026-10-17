package de.werklade.cratoniremote.action

import de.werklade.cratoniremote.protocol.RemoteCommand

/** Result of [ActionDispatcher.dispatch], intended for display and logging. */
sealed interface DispatchResult {
    val command: RemoteCommand

    /** The mapped action was executed without an exception. */
    data class Executed(override val command: RemoteCommand) : DispatchResult

    /** No action is mapped to [command] in V1; no executor was called. */
    data class Ignored(override val command: RemoteCommand) : DispatchResult

    /** The executor threw [cause]; the exception was caught. */
    data class Failed(override val command: RemoteCommand, val cause: Exception) : DispatchResult
}
