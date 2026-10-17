package de.werklade.cratoniremote.action

import de.werklade.cratoniremote.helmet.HelmetFrameSender
import de.werklade.cratoniremote.helmet.HelmetFrames
import de.werklade.cratoniremote.helmet.HelmetLights
import de.werklade.cratoniremote.helmet.TurnSignal

/**
 * [LightExecutor] that turns a button press into one frame for the helmet ([HelmetFrames]) and keeps track of
 * what the lights are doing ([HelmetLights]).
 *
 * The state only moves when a frame was actually handed over: if [HelmetFrameSender.send] answers `false` (no
 * connection), the remembered state stays as it was, so the next press repeats the same intent instead of
 * silently inverting it. Two presses without a helmet therefore still switch the left turn signal *on* when the
 * helmet comes back, which is what the user expects from a toggle that visibly did nothing.
 *
 * Pure Kotlin without Android; the Bluetooth part is behind [HelmetFrameSender].
 *
 * Thread safety: the remote's packets arrive on the BLE thread, while [reset] is called from the coroutine that
 * watches the helmet connection, so the state is guarded by a lock. [HelmetFrameSender.send] runs inside that
 * lock, so two presses can never hand their frames over in one order and remember them in another; it only
 * queues the frame and does not block. [onLightsChanged] is called outside the lock — it publishes to
 * `data.RemoteStatus`, and holding a lock across a listener call is how deadlocks are made.
 *
 * @param sender the helmet connection; frames are sent through it and nowhere else
 * @param onLightsChanged called with every new state, for display (`data.RemoteStatus.onHelmetLights`)
 */
class HelmetLightExecutor(
    private val sender: HelmetFrameSender,
    private val onLightsChanged: (HelmetLights) -> Unit = {},
) : LightExecutor {

    private val lock = Any()

    /** Guarded by [lock]. */
    private var lights = HelmetLights()

    override fun toggleTurnSignal(side: TurnSignal): Boolean = change { current ->
        val next = current.toggled(side)
        next to HelmetFrames.turnSignal(side, next.isOn(side))
    }

    override fun cycleRearLight(): Boolean = change { current ->
        val next = current.rearAdvanced()
        next to HelmetFrames.rearLight(next.rear)
    }

    /**
     * Forgets what the lights were doing, without sending anything.
     *
     * Called when a fresh SPP connection stands: the helmet was out of reach or switched off, so its lights are
     * off and any older belief is worthless. Sending "all off" instead would be three frames the manufacturer
     * app does not send at that point, and the app only ever sends measured frames.
     */
    fun reset() {
        val changed: Boolean
        synchronized(lock) {
            changed = lights != HelmetLights()
            lights = HelmetLights()
        }
        if (changed) onLightsChanged(HelmetLights())
    }

    /** The state the app believes the helmet's lights to be in. */
    fun lights(): HelmetLights = synchronized(lock) { lights }

    private fun change(transition: (HelmetLights) -> Pair<HelmetLights, ByteArray>): Boolean {
        val next: HelmetLights
        synchronized(lock) {
            val (candidate, frame) = transition(lights)
            if (!sender.send(frame)) return false
            lights = candidate
            next = candidate
        }
        onLightsChanged(next)
        return true
    }
}
