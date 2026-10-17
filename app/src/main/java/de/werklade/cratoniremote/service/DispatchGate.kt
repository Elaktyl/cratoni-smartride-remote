package de.werklade.cratoniremote.service

import de.werklade.cratoniremote.data.RemoteState

/**
 * Whether a received button press may be executed in [state]. Pure Kotlin, so the rule is unit tested.
 *
 * It must not be while a confirmation is pending
 * ([de.werklade.cratoniremote.data.RemoteState.pendingConfirmation]): that press is the user saying "this is my
 * remote", not "skip to the next track". It is shown as the last command and ends the waiting, but it starts no
 * action — switching on a stranger's helmet light or answering a call while picking a remote out of a list
 * would be exactly the surprise the confirmation step exists to prevent.
 */
internal fun dispatchAllowed(state: RemoteState): Boolean = state.pendingConfirmation == null
