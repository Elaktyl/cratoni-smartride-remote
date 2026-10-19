package de.werklade.cratoniremote.data

import de.werklade.cratoniremote.ble.RemoteBattery
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The values inside the status texts — numbers and times, no wording.
 *
 * Deliberately pure Kotlin without Android dependencies (no `Context`, no string resources), so the rules are
 * covered by local unit tests. The wording around these values lives in `res/values/strings.xml` and
 * `res/values-de/strings.xml`; which of those strings belongs where is decided by [StatusText] and, for the
 * foreground service notification, by [de.werklade.cratoniremote.service.NotificationText].
 *
 * Everything here formats for a [Locale] and defaults to [Locale.getDefault], the language the system picked
 * for the app, so a value always matches the resources around it. The notification passes the locale of its
 * own configuration instead (see
 * [de.werklade.cratoniremote.service.remoteNotificationText]); there is no second, fixed language any more.
 */
object RemoteStatusText {

    /**
     * After half a day the time of day alone no longer says which day a measurement or command belongs to, so
     * [timestamp] and [shortTimestamp] add the date from then on.
     */
    const val DATE_SHOWN_AFTER_MS: Long = 12L * 60 * 60 * 1000

    /** Decimals of the battery voltage: the remote measures in millivolts, 10 mV is the last digit worth showing. */
    private const val VOLT_DECIMALS = 2

    /**
     * Time of day for [locale], with seconds from [FormatStyle.MEDIUM] and without from [FormatStyle.SHORT]:
     * `"12:34:56"` / `"12:34"` in German, `"12:34:56 PM"` / `"12:34 PM"` in American English.
     *
     * Known limitation: these formats follow the language, not the 12/24-hour switch of the system settings.
     * Honouring that switch would need `android.text.format.DateFormat.getTimeFormat(context)` and therefore a
     * `Context`, which this object deliberately does not have — the rules would then be out of reach of the
     * local unit tests.
     */
    private fun timeFormat(locale: Locale, withSeconds: Boolean): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(if (withSeconds) FormatStyle.MEDIUM else FormatStyle.SHORT)
            .withLocale(locale)

    /**
     * Date and time of day for [locale], e.g. `"12.09.2026, 12:34:56"` in German and
     * `"Sep 12, 2026, 12:34:56 PM"` in American English.
     *
     * The date stays [FormatStyle.MEDIUM] (a four digit year) instead of [FormatStyle.SHORT] (`"12.09.26"`):
     * the two characters saved are not worth a year that can be misread.
     */
    private fun dateTimeFormat(locale: Locale, withSeconds: Boolean): DateTimeFormatter =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, if (withSeconds) FormatStyle.MEDIUM else FormatStyle.SHORT)
            .withLocale(locale)

    /**
     * The battery measurement as one value, e.g. `"60 % (3,74 V)"` in German and `"60% (3.74 V)"` in English.
     *
     * The voltage is in brackets, not behind another
     * [de.werklade.cratoniremote.service.NotificationText.SEPARATOR]: in the notification that separator
     * separates the fields, so `"Akku: 80 % · 3,91 V · Letzter Befehl: …"` would read like four fields instead
     * of three.
     *
     * Without a voltage — the case of a Battery Level 0x2A19 notification — only the percentage is shown.
     * The percentage is the value the remote reported, deliberately not converted (see
     * [de.werklade.cratoniremote.protocol.RemoteStatusFrame] on what is still open about its meaning).
     *
     * The case without any measurement is wording and therefore a string resource (`battery_unknown`), which
     * is why this takes a measurement and not `null`.
     */
    fun batteryValue(battery: RemoteBattery, locale: Locale = Locale.getDefault()): String =
        if (battery.millivolts == null) {
            percent(battery.percent, locale)
        } else {
            "${percent(battery.percent, locale)} (${volts(battery.millivolts, locale)} V)"
        }

    /**
     * [percent] with the per cent sign as [locale] writes it: `"80 %"` in German — with a non-breaking space,
     * so the sign never ends up on the next line — and `"80%"` in English.
     */
    fun percent(percent: Int, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getPercentInstance(locale).format(percent / 100.0)

    /**
     * [millivolts] as volts with two decimals in the convention of [locale], e.g. `3736` → `"3,74"` in German
     * and `"3.74"` in English.
     *
     * Rounding is [RoundingMode.HALF_UP], the arithmetic rule, not the [NumberFormat] default
     * [RoundingMode.HALF_EVEN]: a value ending in 5 mV must not move towards an even digit.
     */
    fun volts(millivolts: Int, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = VOLT_DECIMALS
            maximumFractionDigits = VOLT_DECIMALS
            roundingMode = RoundingMode.HALF_UP
        }.format(millivolts / 1000.0)

    /**
     * Wall clock time of [atEpochMs] with seconds, as [locale] writes it; [zone] is injectable so tests do not
     * depend on the machine.
     */
    fun time(
        atEpochMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = Instant.ofEpochMilli(atEpochMs).atZone(zone).format(timeFormat(locale, withSeconds = true))

    /**
     * [atEpochMs] as `HH:mm`, or as `dd.MM.yyyy, HH:mm` once [showsDate] holds; used for "gemessen um 18:46"
     * under the battery level.
     *
     * Without seconds, because the second of a five minute poll says nothing — but with the date, exactly like
     * [timestamp]: a measurement can easily be many hours old while it is shown. A connection may stand across
     * midnight, and the poll runs on [android.os.SystemClock.uptimeMillis] via `Handler.postDelayed`, which
     * stands still in deep sleep. "gemessen um 23:58" under a value from the night before would be misleading.
     */
    fun shortTimestamp(
        atEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = Instant.ofEpochMilli(atEpochMs).atZone(zone).format(
        if (showsDate(atEpochMs, nowEpochMs)) {
            dateTimeFormat(locale, withSeconds = false)
        } else {
            timeFormat(locale, withSeconds = false)
        },
    )

    /**
     * Whether [atEpochMs] is so long ago ([DATE_SHOWN_AFTER_MS]) that the time of day alone would be misleading.
     *
     * The last command survives the service being stopped, so without this a command from yesterday evening
     * reads like one from a few minutes ago.
     */
    fun showsDate(atEpochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        nowEpochMs - atEpochMs >= DATE_SHOWN_AFTER_MS

    /** [atEpochMs] as a time of day with seconds, with the date in front once [showsDate] holds. */
    fun timestamp(
        atEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = if (showsDate(atEpochMs, nowEpochMs)) {
        Instant.ofEpochMilli(atEpochMs).atZone(zone).format(dateTimeFormat(locale, withSeconds = true))
    } else {
        time(atEpochMs, zone, locale)
    }
}
