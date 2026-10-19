package de.werklade.cratoniremote.data

import de.werklade.cratoniremote.ble.RemoteBattery
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The values inside the status texts: numbers and times. The wording around them is in string resources, and
 * which resource belongs where is decided in [StatusText] and
 * [de.werklade.cratoniremote.service.NotificationText] — see their tests.
 *
 * Every assertion names its locale, so the same assertion holds in Germany and on an English build agent.
 */
class RemoteStatusTextTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private val german = Locale.GERMANY

    /** 24-hour clock like German, so only the number format can differ; see [us] for the 12-hour case. */
    private val uk = Locale.UK

    private val us = Locale.US

    /** German writes a non-breaking space in front of the per cent sign; escaped so no formatter can eat it. */
    private val nbsp = "\u00A0"

    /** 12.09.2026, 12:34:56 Europe/Berlin (CEST, UTC+2), i.e. 10:34:56 UTC. */
    private val at = 1_789_209_296_000L

    /** A minute after [at]: the reference "now" wherever a text must not depend on the clock of the machine. */
    private val shortlyAfter = at + 60_000L

    /** The next morning, well past [RemoteStatusText.DATE_SHOWN_AFTER_MS]. */
    private val nextDay = at + 20 * 60 * 60 * 1000L

    /** One of the measured status frames: `50 0F 42`. */
    private val battery = RemoteBattery(percent = 80, millivolts = 3_906, atEpochMs = at)

    // Battery

    @Test
    fun `the battery value is percent and the voltage in brackets`() {
        assertEquals(
            "60$nbsp% (3,74 V)",
            RemoteStatusText.batteryValue(battery.copy(percent = 60, millivolts = 3_736), german),
        )
        assertEquals(
            "90$nbsp% (4,09 V)",
            RemoteStatusText.batteryValue(battery.copy(percent = 90, millivolts = 4_094), german),
        )
    }

    /** Battery Level 0x2A19 carries no voltage; the percentage alone must still read properly. */
    @Test
    fun `without a voltage only the percentage is shown`() {
        assertEquals("80$nbsp%", RemoteStatusText.batteryValue(battery.copy(millivolts = null), german))
        assertEquals("80%", RemoteStatusText.batteryValue(battery.copy(millivolts = null), us))
    }

    @Test
    fun `volts are rounded to two decimals with a decimal comma`() {
        assertEquals("3,74", RemoteStatusText.volts(3_736, german))
        assertEquals("3,91", RemoteStatusText.volts(3_906, german))
        assertEquals("3,82", RemoteStatusText.volts(3_821, german))
        assertEquals("4,09", RemoteStatusText.volts(4_094, german))
        assertEquals("4,00", RemoteStatusText.volts(4_000, german))
    }

    /** English writes the decimal point, and no space in front of the per cent sign. */
    @Test
    fun `volts and percent follow the given locale`() {
        assertEquals("3.74", RemoteStatusText.volts(3_736, us))
        assertEquals("4.00", RemoteStatusText.volts(4_000, uk))
        assertEquals("80$nbsp%", RemoteStatusText.percent(80, german))
        assertEquals("80%", RemoteStatusText.percent(80, us))
        assertEquals("0%", RemoteStatusText.percent(0, us))
        assertEquals("100%", RemoteStatusText.percent(100, us))
        assertEquals("80% (3.91 V)", RemoteStatusText.batteryValue(battery, us))
    }

    /** Only the locale decides, never the default locale of the machine the test runs on. */
    @Test
    fun `the given locale wins over a foreign default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("3,74", RemoteStatusText.volts(3_736, german))
            assertEquals("80$nbsp% (3,91 V)", RemoteStatusText.batteryValue(battery, german))
            Locale.setDefault(Locale.GERMANY)
            assertEquals("3.74", RemoteStatusText.volts(3_736, us))
            assertEquals("80% (3.91 V)", RemoteStatusText.batteryValue(battery, us))
        } finally {
            Locale.setDefault(previous)
        }
    }

    // Times

    @Test
    fun `the measurement time is an hour and a minute in the given zone`() {
        assertEquals("12:34", RemoteStatusText.shortTimestamp(at, shortlyAfter, zone, german))
        assertEquals("10:34", RemoteStatusText.shortTimestamp(at, shortlyAfter, ZoneId.of("UTC"), german))
    }

    /**
     * A connection can stand across midnight and the poll stands still in deep sleep, so a measurement can be
     * hours old while it is shown; then "gemessen um 23:58" alone would be misleading.
     */
    @Test
    fun `the measurement time adds the date once it is half a day old`() {
        val exactly = at + RemoteStatusText.DATE_SHOWN_AFTER_MS
        assertEquals("12.09.2026, 12:34", RemoteStatusText.shortTimestamp(at, exactly, zone, german))
        assertEquals("12.09.2026, 12:34", RemoteStatusText.shortTimestamp(at, nextDay, zone, german))
    }

    /** Never with seconds: the second of a five minute poll says nothing. */
    @Test
    fun `the measurement time has no seconds in either form`() {
        assertFalse(RemoteStatusText.shortTimestamp(at, shortlyAfter, zone, german).contains(":56"))
        assertFalse(RemoteStatusText.shortTimestamp(at, nextDay, zone, german).contains(":56"))
        assertFalse(RemoteStatusText.shortTimestamp(at, shortlyAfter, zone, us).contains(":56"))
        assertFalse(RemoteStatusText.shortTimestamp(at, nextDay, zone, us).contains(":56"))
    }

    @Test
    fun `time is formatted as hours, minutes and seconds in the given zone`() {
        assertEquals("12:34:56", RemoteStatusText.time(at, zone, german))
        assertEquals("10:34:56", RemoteStatusText.time(at, ZoneId.of("UTC"), german))
    }

    /**
     * The clock format comes from the language: British English counts to 24 like German, American English
     * counts to 12 and names the half of the day. Only the pieces are asserted, not the whole string — the
     * space in front of "PM" is a narrow no-break space in newer CLDR versions and would make the test depend
     * on the JDK rather than on the app.
     */
    @Test
    fun `the clock format follows the given locale`() {
        assertEquals("12:34:56", RemoteStatusText.time(at, zone, uk))
        val american = RemoteStatusText.time(at, zone, us)
        assertTrue(american, american.startsWith("12:34:56"))
        assertTrue(american, american.endsWith("PM"))
        val americanWithDate = RemoteStatusText.timestamp(at, nextDay, zone, us)
        assertTrue(americanWithDate, americanWithDate.startsWith("Sep 12, 2026"))
        assertTrue(americanWithDate, americanWithDate.endsWith("PM"))
        val british = RemoteStatusText.shortTimestamp(at, nextDay, zone, uk)
        assertTrue(british, british.startsWith("12 Sep"))
        assertTrue(british, british.endsWith("2026, 12:34"))
    }

    @Test
    fun `timestamp stays a time of day while the command is recent`() {
        assertEquals("12:34:56", RemoteStatusText.timestamp(at, shortlyAfter, zone, german))
        assertFalse(RemoteStatusText.showsDate(at, shortlyAfter))
    }

    @Test
    fun `timestamp adds the date once the command is half a day old`() {
        val exactly = at + RemoteStatusText.DATE_SHOWN_AFTER_MS
        assertTrue(RemoteStatusText.showsDate(at, exactly))
        assertEquals("12.09.2026, 12:34:56", RemoteStatusText.timestamp(at, exactly, zone, german))
        assertEquals("12.09.2026, 12:34:56", RemoteStatusText.timestamp(at, nextDay, zone, german))
    }

    @Test
    fun `just below half a day the date is still left out`() {
        val justBefore = at + RemoteStatusText.DATE_SHOWN_AFTER_MS - 1
        assertFalse(RemoteStatusText.showsDate(at, justBefore))
        assertEquals("12:34:56", RemoteStatusText.timestamp(at, justBefore, zone, german))
    }
}
