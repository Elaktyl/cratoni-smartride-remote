package de.werklade.cratoniremote.action

import de.werklade.cratoniremote.helmet.TurnSignal

/**
 * The remote's light buttons, which switch the lights of the helmet (docs/architektur.md §13).
 *
 * The remote only reports "button pressed", so both methods are toggles and the implementation keeps the
 * state: two steps for a turn signal (on/off), three for the rear light (off → solid → blinking → off).
 *
 * Implementations may throw; [ActionDispatcher] reports exceptions as [DispatchResult.Failed].
 *
 * @see HelmetLightExecutor
 */
interface LightExecutor {

    /**
     * Switches the turn signal of [side] on or off, whichever is next.
     *
     * @return `true` if a frame was sent, `false` if there was nothing to do — no connection to the helmet.
     *   [ActionDispatcher] turns `false` into [DispatchResult.Ignored]: a press with the helmet switched off is
     *   not an error (decision of the project owner, 26.09.2026: log and screen, no sound).
     */
    fun toggleTurnSignal(side: TurnSignal): Boolean

    /**
     * Advances the rear light to the next of its three states.
     *
     * @return as in [toggleTurnSignal]
     */
    fun cycleRearLight(): Boolean
}
