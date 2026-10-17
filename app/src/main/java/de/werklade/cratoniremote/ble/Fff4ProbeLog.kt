package de.werklade.cratoniremote.ble

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Append-only diagnostic file for the FFF4 status frame (docs/architektur.md §11): one line per connection,
 * so the raw bytes survive without a PC attached — logcat is a ring buffer and gone after a reboot.
 *
 * Line format `2026-09-12T12:31:05+02:00;5A 10 03`: ISO-8601 with offset, semicolon, hex exactly as in the log
 * ([BleHex]). Nothing is interpreted here either.
 *
 * The file is capped by [MAX_LINES] and [MAX_BYTES]; the oldest lines are dropped first. [append] rewrites the
 * whole file, which is cheap at this size and happens once per connection. It is not crash-safe (a crash in the
 * middle of the rewrite can lose the file content) — acceptable for a diagnostic aid, nothing depends on it.
 *
 * Formatting and truncation are pure functions and unit tested; only [append] touches the file system.
 */
internal object Fff4ProbeLog {

    /** File name inside the app-private `filesDir`; no permission is needed for that directory. */
    const val FILE_NAME = "fff4-probe.log"

    /** At most this many lines are kept, newest last. */
    const val MAX_LINES = 1_000

    /** Upper bound for the file size in bytes, including the line separators. */
    const val MAX_BYTES = 64 * 1024

    private const val SEPARATOR = ';'
    private const val LINE_BREAK = "\n"

    /**
     * One log line for [hex] at [epochMillis] in [zone], truncated to whole seconds.
     * A UTC zone is formatted as `Z`, as ISO-8601 prescribes.
     */
    fun line(epochMillis: Long, zone: ZoneId, hex: String): String {
        val timestamp = Instant.ofEpochMilli(epochMillis)
            .atZone(zone)
            .truncatedTo(ChronoUnit.SECONDS)
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        return "$timestamp$SEPARATOR$hex"
    }

    /**
     * The tail of [lines] that fits into [MAX_LINES] and [MAX_BYTES]; the oldest lines are dropped first.
     * The newest line is always kept, even if it alone were larger than [MAX_BYTES].
     */
    fun trim(lines: List<String>): List<String> {
        if (lines.isEmpty()) return lines
        var start = maxOf(0, lines.size - MAX_LINES)
        var bytes = (start until lines.size).sumOf { byteLength(lines[it]) }
        while (start < lines.size - 1 && bytes > MAX_BYTES) {
            bytes -= byteLength(lines[start])
            start++
        }
        return if (start == 0) lines else lines.subList(start, lines.size)
    }

    /** Bytes this line occupies in the file, including its line break. */
    private fun byteLength(line: String): Int = line.toByteArray(Charsets.UTF_8).size + LINE_BREAK.length

    /**
     * Appends [line] to [file] and applies [trim]. Blocking file I/O; callers must not run it on a latency
     * sensitive thread. Throws on I/O errors, the caller decides what to do.
     */
    fun append(file: File, line: String) {
        val existing = if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
        val kept = trim(existing + line)
        file.writeText(kept.joinToString(separator = LINE_BREAK, postfix = LINE_BREAK), Charsets.UTF_8)
    }
}
