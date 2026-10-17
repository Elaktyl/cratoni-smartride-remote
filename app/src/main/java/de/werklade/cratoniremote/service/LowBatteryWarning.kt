package de.werklade.cratoniremote.service

/**
 * Decides whether a battery measurement of the remote deserves a warning (docs/architektur.md §8 wording:
 * pure decision logic, no Android dependency, so it is covered by local unit tests).
 *
 * The state that has to survive between measurements — and process restarts, see [LowBatteryNotifier] — is one
 * number: the threshold that was warned about last. Everything else is derived from it and the current value.
 *
 * Sequence for a discharging remote: 30 % → nothing, 20 % → warn 20, 20 % again → nothing, 10 % → warn 10,
 * 0 % → warn 0. After charging to 30 % the 20 % threshold is armed again ([isRearmed]).
 */
object LowBatteryWarning {

    /**
     * Thresholds, descending. The remote reports in steps of ten (byte 0 of the FFF4 status frame fell 80 → 70 →
     * … over two weeks of the device test), so every threshold is hit exactly and none can be skipped by the
     * value jumping past it — a jump would still warn about the lowest threshold reached, see [shouldWarn].
     */
    val THRESHOLDS: List<Int> = listOf(20, 10, 0)

    /**
     * How far above a threshold the reported value has to climb for that threshold to count as charged and
     * become armed again ([isRearmed]).
     *
     * Ten percentage points, i.e. exactly one reported step: less would make a value flickering between two
     * neighbouring steps (20 → 30 → 20) warn again and again, more would need two charging steps and would, for
     * the 20 % threshold, only arm at 40 % — a remote that is charged briefly would then never warn again.
     *
     * Consequence, intended: charging from 0 % to 10 % arms the 0 % threshold again and the 10 % threshold is
     * warned about, because 10 % is still a value worth knowing about.
     */
    const val REARM_MARGIN = 10

    /** Stored value meaning "nothing warned about yet"; no threshold can be negative. */
    const val NOTHING_WARNED = -1

    /**
     * The threshold to warn about, or `null` if there is nothing to warn about.
     *
     * @param lastWarnedLevel threshold of the last warning, `null` if there was none (yet) or if it was armed
     *   again after charging ([isRearmed]); the caller keeps this across process restarts
     * @param currentPercent the value just reported by the remote; outside `0..100` nothing is warned about,
     *   because such a value cannot be trusted (the parsers reject it, this is the second line of defence)
     * @return the threshold ([THRESHOLDS]) the caller should warn about and store, or `null`
     */
    fun shouldWarn(lastWarnedLevel: Int?, currentPercent: Int): Int? {
        if (currentPercent !in 0..100) return null
        // The lowest threshold the value has reached: 15 % → 20, 10 % → 10, 5 % → 10, 0 % → 0.
        val reached = THRESHOLDS.filter { currentPercent <= it }.minOrNull() ?: return null
        return when {
            lastWarnedLevel == null -> reached
            // Dropped to a lower threshold: worth its own warning.
            reached < lastWarnedLevel -> reached
            // Same or a higher threshold, and not charged enough to be armed again: stay quiet.
            else -> null
        }
    }

    /**
     * Whether [currentPercent] is far enough above [lastWarnedLevel] ([REARM_MARGIN]) for that threshold to be
     * warned about again later. Without this there would never be a second warning after the first charge.
     */
    fun isRearmed(lastWarnedLevel: Int, currentPercent: Int): Boolean =
        currentPercent in 0..100 && currentPercent >= lastWarnedLevel + REARM_MARGIN
}
