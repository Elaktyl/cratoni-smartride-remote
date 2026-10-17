package de.werklade.cratoniremote.helmet

/** The helmet's two turn signals. */
enum class TurnSignal { LEFT, RIGHT }

/**
 * The three states of the helmet's rear light, in the order one button press walks through them
 * (docs/architektur.md §13): [OFF] → [SOLID] → [BLINKING] → [OFF].
 */
enum class RearLight {
    OFF,
    SOLID,
    BLINKING,
    ;

    /** The next state of the cycle. */
    fun next(): RearLight = entries[(ordinal + 1) % entries.size]
}

/**
 * What the app believes the helmet's lights are doing. Pure Kotlin, value-based equality.
 *
 * The app has to keep this itself: the remote only reports "button pressed" and the helmet answers nothing
 * (docs/architektur.md §13). It is therefore a belief, not a measurement — it is correct as long as nothing
 * else switches the lights (the manufacturer app, or the helmet's own buttons if it has any) and it starts
 * over at "everything off" whenever a fresh SPP connection is established.
 *
 * Turn signals are independent of each other; both can be on at the same time, and nothing switches them off
 * automatically, which is the behaviour of the manufacturer app (decision of the project owner, 26.09.2026).
 */
data class HelmetLights(
    val left: Boolean = false,
    val right: Boolean = false,
    val rear: RearLight = RearLight.OFF,
) {

    /** `true` if any of the three lights is on; used for the status line. */
    val anyOn: Boolean get() = left || right || rear != RearLight.OFF

    fun isOn(side: TurnSignal): Boolean = when (side) {
        TurnSignal.LEFT -> left
        TurnSignal.RIGHT -> right
    }

    /** The state after a press on the turn signal button of [side]: that side toggles, nothing else changes. */
    fun toggled(side: TurnSignal): HelmetLights = when (side) {
        TurnSignal.LEFT -> copy(left = !left)
        TurnSignal.RIGHT -> copy(right = !right)
    }

    /** The state after a press on the rear light button: [RearLight.next], nothing else changes. */
    fun rearAdvanced(): HelmetLights = copy(rear = rear.next())
}
