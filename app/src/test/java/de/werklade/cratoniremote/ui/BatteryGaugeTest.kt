package de.werklade.cratoniremote.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Only the segment arithmetic of [BatteryGauge] is covered here: it is pure Kotlin, while the drawing itself
 * needs an instrumented or screenshot test (see the previews in `BatteryGauge.kt`).
 */
class BatteryGaugeTest {

    @Test
    fun `an unknown level fills no segment`() {
        assertEquals(0, filledBatterySegments(null))
    }

    @Test
    fun `an empty battery fills no segment`() {
        assertEquals(0, filledBatterySegments(0))
    }

    /** Rounded up, so a remote that still has charge never looks empty. */
    @Test
    fun `one percent already fills one segment`() {
        assertEquals(1, filledBatterySegments(1))
    }

    @Test
    fun `the measured multiples of ten fill exactly that many segments`() {
        assertEquals(6, filledBatterySegments(60))
        assertEquals(7, filledBatterySegments(70))
        assertEquals(8, filledBatterySegments(80))
        assertEquals(9, filledBatterySegments(90))
    }

    @Test
    fun `a value between two steps is rounded up`() {
        assertEquals(6, filledBatterySegments(55))
        assertEquals(6, filledBatterySegments(51))
        assertEquals(5, filledBatterySegments(50))
    }

    @Test
    fun `a full battery fills every segment`() {
        assertEquals(BATTERY_SEGMENTS, filledBatterySegments(100))
    }

    @Test
    fun `values outside the valid range stay inside the frame`() {
        assertEquals(0, filledBatterySegments(-5))
        assertEquals(BATTERY_SEGMENTS, filledBatterySegments(255))
    }

    /** The colour switches at this level, so the threshold itself must not drift unnoticed. */
    @Test
    fun `the low threshold is twenty percent, which is two segments`() {
        assertEquals(20, BATTERY_LOW_PERCENT)
        assertEquals(2, filledBatterySegments(BATTERY_LOW_PERCENT))
    }
}
