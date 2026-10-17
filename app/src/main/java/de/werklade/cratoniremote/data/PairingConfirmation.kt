package de.werklade.cratoniremote.data

/**
 * How long the app waits for the confirmation press on the chosen remote (decision of the project owner,
 * 16.10.2026): long enough to take the remote out of a pocket, short enough that a wrong choice is undone
 * while the user is still looking at the screen.
 */
const val CONFIRMATION_TIMEOUT_MS: Long = 30_000L

/**
 * A remote that was chosen in the association dialog and still has to be confirmed by a press on it.
 *
 * Needed because the dialog shows a list: several `Remote control-XXXX` look alike on a group ride, so the
 * chosen association only becomes "the remote" once a button press arrives through its own GATT connection.
 * Until then nothing is dispatched ([de.werklade.cratoniremote.service.dispatchAllowed]) and nothing is
 * persisted; without a press the association is removed again.
 *
 * @param associationId ID of the `android.companion.AssociationInfo` that is being confirmed
 * @param label what the user sees, e.g. `"Remote control-3D2C"`; only for display, never for identification
 * @param deadlineElapsedMs end of the waiting time on [android.os.SystemClock.elapsedRealtime]. That clock is
 *   monotonic and keeps running in deep sleep, unlike `uptimeMillis`, and cannot be moved by the user or by the
 *   network, unlike `currentTimeMillis` — a countdown must not be shortened or extended by a clock correction.
 */
data class PendingConfirmation(
    val associationId: Int,
    val label: String,
    val deadlineElapsedMs: Long,
) {

    /**
     * Whole seconds left at [nowElapsedMs], never negative and rounded up, so the first second of the wait
     * reads as the full timeout and the countdown only shows `0` when the time really is up.
     */
    fun remainingSeconds(nowElapsedMs: Long): Int {
        val remaining = deadlineElapsedMs - nowElapsedMs
        if (remaining <= 0L) return 0
        return ((remaining + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
    }

    /** Whether the waiting time is over at [nowElapsedMs]; the deadline itself counts as over. */
    fun isExpired(nowElapsedMs: Long): Boolean = nowElapsedMs >= deadlineElapsedMs

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
