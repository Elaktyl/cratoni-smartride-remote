package de.werklade.cratoniremote.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryWarningTest {

    @Test
    fun `above the highest threshold nothing is warned about`() {
        for (percent in 21..100) {
            assertNull("$percent %", LowBatteryWarning.shouldWarn(null, percent))
        }
    }

    @Test
    fun `the first time a threshold is reached it is warned about`() {
        assertEquals(20, LowBatteryWarning.shouldWarn(null, 20))
        assertEquals(10, LowBatteryWarning.shouldWarn(null, 10))
        assertEquals(0, LowBatteryWarning.shouldWarn(null, 0))
    }

    /** The remote reports in steps of ten; values in between are covered anyway. */
    @Test
    fun `a value between thresholds warns about the lowest one it reached`() {
        assertEquals(20, LowBatteryWarning.shouldWarn(null, 15))
        assertEquals(10, LowBatteryWarning.shouldWarn(null, 5))
        assertEquals(10, LowBatteryWarning.shouldWarn(null, 1))
    }

    /** The five minute poll delivers the same value over and over; only the first one may warn. */
    @Test
    fun `the same threshold is warned about only once`() {
        assertNull(LowBatteryWarning.shouldWarn(20, 20))
        assertNull(LowBatteryWarning.shouldWarn(20, 15))
        assertNull(LowBatteryWarning.shouldWarn(10, 10))
        assertNull(LowBatteryWarning.shouldWarn(0, 0))
    }

    @Test
    fun `dropping to the next threshold warns again`() {
        assertEquals(10, LowBatteryWarning.shouldWarn(20, 10))
        assertEquals(0, LowBatteryWarning.shouldWarn(10, 0))
        // A value that skips a threshold warns about the lowest one it reached, not about both.
        assertEquals(0, LowBatteryWarning.shouldWarn(20, 0))
    }

    /** A value above the last warned threshold but not yet a charge: still quiet. */
    @Test
    fun `rising inside the thresholds does not warn`() {
        assertNull(LowBatteryWarning.shouldWarn(0, 10))
        assertNull(LowBatteryWarning.shouldWarn(10, 20))
    }

    @Test
    fun `a threshold is armed again once the value climbed a whole step above it`() {
        assertTrue(LowBatteryWarning.isRearmed(20, 30))
        assertTrue(LowBatteryWarning.isRearmed(20, 100))
        assertTrue(LowBatteryWarning.isRearmed(10, 20))
        assertTrue(LowBatteryWarning.isRearmed(0, 10))
        assertFalse(LowBatteryWarning.isRearmed(20, 29))
        assertFalse(LowBatteryWarning.isRearmed(20, 20))
        assertFalse(LowBatteryWarning.isRearmed(10, 0))
    }

    /** Without this there would never be a second warning after the first charge. */
    @Test
    fun `after charging the same threshold warns again`() {
        var lastWarned: Int? = null
        // Discharging: 30 → 20 warns.
        assertNull(LowBatteryWarning.shouldWarn(lastWarned, 30))
        lastWarned = LowBatteryWarning.shouldWarn(lastWarned, 20)
        assertEquals(20, lastWarned)
        // Charged to 60: the stored threshold is armed again, so the caller drops it.
        assertTrue(LowBatteryWarning.isRearmed(lastWarned!!, 60))
        lastWarned = null
        // Next discharge warns once more.
        assertEquals(20, LowBatteryWarning.shouldWarn(lastWarned, 20))
    }

    @Test
    fun `implausible values warn about nothing`() {
        assertNull(LowBatteryWarning.shouldWarn(null, -1))
        assertNull(LowBatteryWarning.shouldWarn(null, 101))
        assertNull(LowBatteryWarning.shouldWarn(20, -5))
        assertNull(LowBatteryWarning.shouldWarn(20, 255))
        assertFalse(LowBatteryWarning.isRearmed(20, 200))
    }

    @Test
    fun `every threshold can be warned about from scratch and is descending`() {
        assertEquals(LowBatteryWarning.THRESHOLDS.sortedDescending(), LowBatteryWarning.THRESHOLDS)
        for (threshold in LowBatteryWarning.THRESHOLDS) {
            assertEquals(threshold, LowBatteryWarning.shouldWarn(null, threshold))
        }
    }

    /** The stored "nothing warned yet" must never look like a threshold. */
    @Test
    fun `the empty marker is no threshold`() {
        assertFalse(LowBatteryWarning.NOTHING_WARNED in LowBatteryWarning.THRESHOLDS)
    }
}
