package de.werklade.cratoniremote.ble

import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic of [Fff4ProbeLog]: line format and truncation rule. No file system involved. */
class Fff4ProbeLogTest {

    private fun millisOf(isoOffsetDateTime: String): Long =
        OffsetDateTime.parse(isoOffsetDateTime).toInstant().toEpochMilli()

    @Test
    fun `line is ISO timestamp with offset, semicolon and hex`() {
        val millis = millisOf("2026-09-12T12:31:05+02:00")
        assertEquals(
            "2026-09-12T12:31:05+02:00;5A 10 03",
            Fff4ProbeLog.line(millis, ZoneOffset.ofHours(2), "5A 10 03"),
        )
    }

    @Test
    fun `line is truncated to whole seconds`() {
        val millis = millisOf("2026-09-12T12:31:05+02:00") + 999
        assertEquals(
            "2026-09-12T12:31:05+02:00;5A 10 0B",
            Fff4ProbeLog.line(millis, ZoneOffset.ofHours(2), "5A 10 0B"),
        )
    }

    @Test
    fun `UTC is formatted as Z`() {
        val millis = millisOf("2026-09-12T10:31:05Z")
        assertEquals("2026-09-12T10:31:05Z;5A 10 07", Fff4ProbeLog.line(millis, ZoneOffset.UTC, "5A 10 07"))
    }

    @Test
    fun `line contains exactly one separator so it stays parsable`() {
        val line = Fff4ProbeLog.line(millisOf("2026-09-12T12:31:05+02:00"), ZoneOffset.ofHours(2), "5A 10 03")
        assertEquals(1, line.count { it == ';' })
        assertEquals("5A 10 03", line.substringAfter(';'))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(emptyList<String>(), Fff4ProbeLog.trim(emptyList()))
    }

    @Test
    fun `lines below both limits are kept unchanged`() {
        val lines = List(10) { "line $it" }
        assertEquals(lines, Fff4ProbeLog.trim(lines))
    }

    @Test
    fun `exactly MAX_LINES lines are kept`() {
        val lines = List(Fff4ProbeLog.MAX_LINES) { "$it" }
        assertEquals(lines, Fff4ProbeLog.trim(lines))
    }

    @Test
    fun `oldest lines are dropped above MAX_LINES`() {
        val lines = List(Fff4ProbeLog.MAX_LINES + 5) { "$it" }
        val kept = Fff4ProbeLog.trim(lines)
        assertEquals(Fff4ProbeLog.MAX_LINES, kept.size)
        assertEquals("5", kept.first())
        assertEquals("${Fff4ProbeLog.MAX_LINES + 4}", kept.last())
    }

    @Test
    fun `oldest lines are dropped above MAX_BYTES`() {
        // 100 lines of 1 KB each: far below MAX_LINES, far above MAX_BYTES.
        val lines = List(100) { "$it".padEnd(1_023, 'x') }
        val kept = Fff4ProbeLog.trim(lines)
        val bytes = kept.sumOf { it.toByteArray().size + 1 }
        assertTrue("kept $bytes bytes", bytes <= Fff4ProbeLog.MAX_BYTES)
        assertTrue("dropped nothing", kept.size < lines.size)
        assertEquals(lines.last(), kept.last())
        // Nothing is dropped unnecessarily: one more line would exceed the limit.
        val oneMore = lines[lines.size - kept.size - 1]
        assertTrue(bytes + oneMore.toByteArray().size + 1 > Fff4ProbeLog.MAX_BYTES)
    }

    @Test
    fun `the newest line is kept even if it alone exceeds MAX_BYTES`() {
        val huge = "x".repeat(Fff4ProbeLog.MAX_BYTES + 10)
        assertEquals(listOf(huge), Fff4ProbeLog.trim(listOf("old", huge)))
    }

    @Test
    fun `byte limit counts UTF-8 bytes, not characters`() {
        // 2 bytes per character plus the line break: 21 846 lines of 3 bytes would fit by character count.
        val line = "ä".repeat(16_384)
        val kept = Fff4ProbeLog.trim(listOf(line, line, line))
        assertEquals(1, kept.size)
    }
}
