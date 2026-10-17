package de.werklade.cratoniremote.helmet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state model of the lights: two steps per turn signal, three for the rear light, nothing automatic. */
class HelmetLightsTest {

    @Test
    fun `everything is off by default`() {
        val lights = HelmetLights()

        assertFalse(lights.left)
        assertFalse(lights.right)
        assertEquals(RearLight.OFF, lights.rear)
        assertFalse(lights.anyOn)
    }

    @Test
    fun `a turn signal toggles on and off again`() {
        for (side in TurnSignal.entries) {
            val on = HelmetLights().toggled(side)
            assertTrue("$side on", on.isOn(side))
            assertTrue("$side anyOn", on.anyOn)

            val off = on.toggled(side)
            assertFalse("$side off", off.isOn(side))
            assertEquals("$side back to start", HelmetLights(), off)
        }
    }

    @Test
    fun `a turn signal does not touch the other side or the rear light`() {
        val start = HelmetLights(right = true, rear = RearLight.BLINKING)

        val next = start.toggled(TurnSignal.LEFT)

        assertEquals(HelmetLights(left = true, right = true, rear = RearLight.BLINKING), next)
    }

    /** Both turn signals can be on at once; nothing switches one off because the other comes on. */
    @Test
    fun `both turn signals can be on at the same time`() {
        val both = HelmetLights().toggled(TurnSignal.LEFT).toggled(TurnSignal.RIGHT)

        assertEquals(HelmetLights(left = true, right = true), both)
    }

    @Test
    fun `rear light cycles off solid blinking off`() {
        var lights = HelmetLights()

        lights = lights.rearAdvanced()
        assertEquals(RearLight.SOLID, lights.rear)

        lights = lights.rearAdvanced()
        assertEquals(RearLight.BLINKING, lights.rear)

        lights = lights.rearAdvanced()
        assertEquals(RearLight.OFF, lights.rear)
        assertEquals(HelmetLights(), lights)
    }

    @Test
    fun `rear light does not touch the turn signals`() {
        val start = HelmetLights(left = true, right = true)

        assertEquals(start.copy(rear = RearLight.SOLID), start.rearAdvanced())
    }

    @Test
    fun `next covers every rear light state and returns to the start`() {
        var state = RearLight.OFF
        repeat(RearLight.entries.size) { state = state.next() }

        assertEquals(RearLight.OFF, state)
        assertEquals(RearLight.entries.size, RearLight.entries.map { it.next() }.toSet().size)
    }

    @Test
    fun `anyOn holds for every single light`() {
        assertTrue(HelmetLights(left = true).anyOn)
        assertTrue(HelmetLights(right = true).anyOn)
        assertTrue(HelmetLights(rear = RearLight.SOLID).anyOn)
        assertTrue(HelmetLights(rear = RearLight.BLINKING).anyOn)
        assertFalse(HelmetLights(rear = RearLight.OFF).anyOn)
    }
}
