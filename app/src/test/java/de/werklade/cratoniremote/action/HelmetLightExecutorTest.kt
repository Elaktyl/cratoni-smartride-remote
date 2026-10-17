package de.werklade.cratoniremote.action

import de.werklade.cratoniremote.helmet.HelmetFrameSender
import de.werklade.cratoniremote.helmet.HelmetLights
import de.werklade.cratoniremote.helmet.RearLight
import de.werklade.cratoniremote.helmet.TurnSignal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which frame a press produces, and — just as important — that a press that could not be sent does not move
 * the remembered state.
 */
class HelmetLightExecutorTest {

    /** Every frame the executor handed over, in order. */
    private val sent = mutableListOf<ByteArray>()

    /** What the fake connection answers: `false` stands for "no connection to the helmet". */
    private var connected = true

    private val published = mutableListOf<HelmetLights>()

    private val sender = object : HelmetFrameSender {
        override fun send(frame: ByteArray): Boolean {
            if (!connected) return false
            sent += frame
            return true
        }
    }

    private val executor = HelmetLightExecutor(sender) { published += it }

    @Test
    fun `left turn signal sends on then off`() {
        assertTrue(executor.toggleTurnSignal(TurnSignal.LEFT))
        assertTrue(executor.toggleTurnSignal(TurnSignal.LEFT))

        assertFrames(bytes(0x0F, 0x01, 0x01, 0x08, 0x19), bytes(0x0F, 0x01, 0x01, 0x00, 0x11))
        assertEquals(listOf(HelmetLights(left = true), HelmetLights()), published)
    }

    @Test
    fun `right turn signal sends on then off`() {
        executor.toggleTurnSignal(TurnSignal.RIGHT)
        executor.toggleTurnSignal(TurnSignal.RIGHT)

        assertFrames(bytes(0x0F, 0x01, 0x02, 0x08, 0x1A), bytes(0x0F, 0x01, 0x02, 0x00, 0x12))
    }

    @Test
    fun `the two turn signals are independent`() {
        executor.toggleTurnSignal(TurnSignal.LEFT)
        executor.toggleTurnSignal(TurnSignal.RIGHT)

        assertFrames(bytes(0x0F, 0x01, 0x01, 0x08, 0x19), bytes(0x0F, 0x01, 0x02, 0x08, 0x1A))
        assertEquals(HelmetLights(left = true, right = true), executor.lights())
    }

    @Test
    fun `rear light walks through solid blinking off`() {
        repeat(4) { assertTrue(executor.cycleRearLight()) }

        assertFrames(
            bytes(0x0F, 0x01, 0x04, 0xFF, 0x13),
            bytes(0x0F, 0x01, 0x04, 0xFE, 0x12),
            bytes(0x0F, 0x01, 0x04, 0x00, 0x14),
            bytes(0x0F, 0x01, 0x04, 0xFF, 0x13),
        )
        assertEquals(HelmetLights(rear = RearLight.SOLID), executor.lights())
    }

    /**
     * The important failure case: the helmet is off. Nothing is sent, nothing is published, and the next press
     * still means "switch the left turn signal on" — a state that had moved anyway would invert the meaning of
     * every further press.
     */
    @Test
    fun `a press without a connection changes nothing`() {
        connected = false

        assertFalse(executor.toggleTurnSignal(TurnSignal.LEFT))
        assertFalse(executor.cycleRearLight())

        assertEquals(emptyList<ByteArray>(), sent)
        assertEquals(emptyList<HelmetLights>(), published)
        assertEquals(HelmetLights(), executor.lights())

        connected = true
        assertTrue(executor.toggleTurnSignal(TurnSignal.LEFT))
        assertFrames(bytes(0x0F, 0x01, 0x01, 0x08, 0x19))
    }

    @Test
    fun `a failed press in the middle of the rear light cycle keeps the state`() {
        executor.cycleRearLight()
        connected = false

        assertFalse(executor.cycleRearLight())
        assertEquals(HelmetLights(rear = RearLight.SOLID), executor.lights())

        connected = true
        executor.cycleRearLight()
        assertFrames(bytes(0x0F, 0x01, 0x04, 0xFF, 0x13), bytes(0x0F, 0x01, 0x04, 0xFE, 0x12))
    }

    @Test
    fun `reset forgets the state without sending anything`() {
        executor.toggleTurnSignal(TurnSignal.LEFT)
        executor.cycleRearLight()
        val framesBefore = sent.size
        published.clear()

        executor.reset()

        assertEquals(HelmetLights(), executor.lights())
        assertEquals(framesBefore, sent.size)
        assertEquals(listOf(HelmetLights()), published)
    }

    /** Nothing to forget, nothing to publish: a reset on a fresh executor must not produce an update. */
    @Test
    fun `reset without a change publishes nothing`() {
        executor.reset()

        assertEquals(emptyList<HelmetLights>(), sent)
        assertEquals(emptyList<HelmetLights>(), published)
    }

    /** After a reset the next press is an "on" again, whatever the lights did before. */
    @Test
    fun `after reset the left turn signal is switched on again`() {
        executor.toggleTurnSignal(TurnSignal.LEFT)
        executor.reset()

        executor.toggleTurnSignal(TurnSignal.LEFT)

        assertFrames(bytes(0x0F, 0x01, 0x01, 0x08, 0x19), bytes(0x0F, 0x01, 0x01, 0x08, 0x19))
    }

    private fun assertFrames(vararg expected: ByteArray) {
        assertEquals("number of frames, got ${sent.map { it.contentToString() }}", expected.size, sent.size)
        expected.forEachIndexed { index, frame -> assertArrayEquals("frame $index", frame, sent[index]) }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
