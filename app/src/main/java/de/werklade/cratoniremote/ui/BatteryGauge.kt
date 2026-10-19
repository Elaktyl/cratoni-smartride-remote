package de.werklade.cratoniremote.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.werklade.cratoniremote.R
import de.werklade.cratoniremote.ble.RemoteBattery
import de.werklade.cratoniremote.data.RemoteStatusText

/** Segments of the gauge; ten, so one segment stands for the ten percent steps the remote reports. */
internal const val BATTERY_SEGMENTS = 10

/** At or below this percentage the filled segments turn to the error colour. */
internal const val BATTERY_LOW_PERCENT = 20

/** Empty segments stay visible as a faint track, so the scale can be read at 0 % as well. */
private const val EMPTY_SEGMENT_ALPHA = 0.22f

/** Width of the gauge relative to its height; leaves about three pixels per segment at the default font size. */
private const val GAUGE_ASPECT = 2.4f

/** Only used if the theme leaves `titleMedium.fontSize` unspecified, which Material 3 does not. */
private val FALLBACK_FONT_SIZE = 16.sp

/**
 * Filled segments for [percent]: rounded up, so every remaining charge lights at least one segment and only an
 * empty or unknown battery shows none. 0 % → 0, 1 % → 1, 55 % → 6, 60 % → 6, 100 % → 10.
 *
 * Values outside `0..100` cannot come from the parsers ([de.werklade.cratoniremote.ble.BatteryLevel],
 * [de.werklade.cratoniremote.protocol.RemoteStatusFrameParser] both reject them) and are clamped here anyway, so
 * a future source cannot draw outside the frame.
 */
internal fun filledBatterySegments(percent: Int?): Int {
    if (percent == null || percent <= 0) return 0
    val coerced = percent.coerceAtMost(100)
    return ((coerced * BATTERY_SEGMENTS + 99) / 100).coerceAtMost(BATTERY_SEGMENTS)
}

/**
 * Battery symbol drawn with [Canvas]: a frame with a terminal and [BATTERY_SEGMENTS] segments, of which
 * [filledBatterySegments] are filled. No image file and no additional dependency.
 *
 * Size follows the text next to it: height is the font size of `titleMedium` in dp, read through
 * [LocalDensity], so the symbol grows with the system font scale instead of being cut off at a fixed height.
 *
 * Colours come from the Material 3 scheme: the frame from [LocalContentColor] (the content colour of the card it
 * sits in, therefore readable in light and dark mode), the filling from `primary` and from `error` at
 * [BATTERY_LOW_PERCENT] or less.
 *
 * Deliberately without a `contentDescription`, i.e. decorative: the text next to it carries the same information
 * as words ("60 % (3,74 V)"), and the row around both is a merged semantics node. A description here would make
 * a screen reader say the percentage twice.
 *
 * @param percent battery level as reported by the remote, `null` if unknown — then the symbol stays empty
 */
@Composable
fun BatteryGauge(percent: Int?, modifier: Modifier = Modifier) {
    val filled = filledBatterySegments(percent)
    val fillColor = if (percent != null && percent <= BATTERY_LOW_PERCENT) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val outlineColor = LocalContentColor.current
    val emptyColor = outlineColor.copy(alpha = EMPTY_SEGMENT_ALPHA)
    // isSp instead of takeOrElse: that only catches Unspecified, while a theme with an Em font size would make
    // toDp() throw. Any unit other than sp therefore falls back as well.
    val themeFontSize = MaterialTheme.typography.titleMedium.fontSize
    val fontSize = if (themeFontSize.isSp) themeFontSize else FALLBACK_FONT_SIZE
    val height = with(LocalDensity.current) { fontSize.toDp() }

    Canvas(modifier = modifier.size(width = height * GAUGE_ASPECT, height = height)) {
        val strokeWidth = size.height * 0.08f
        val nubWidth = size.width * 0.055f
        val nubHeight = size.height * 0.42f
        val bodyWidth = size.width - nubWidth

        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
            size = Size(bodyWidth - strokeWidth, size.height - strokeWidth),
            cornerRadius = CornerRadius(size.height * 0.2f),
            style = Stroke(width = strokeWidth),
        )
        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(bodyWidth, (size.height - nubHeight) / 2f),
            size = Size(nubWidth, nubHeight),
            cornerRadius = CornerRadius(nubWidth / 2f),
        )

        val inset = strokeWidth * 2f
        val innerWidth = bodyWidth - 2f * inset
        val innerHeight = size.height - 2f * inset
        val gap = innerWidth * 0.018f
        val segmentWidth = (innerWidth - gap * (BATTERY_SEGMENTS - 1)) / BATTERY_SEGMENTS
        repeat(BATTERY_SEGMENTS) { index ->
            drawRoundRect(
                color = if (index < filled) fillColor else emptyColor,
                topLeft = Offset(inset + index * (segmentWidth + gap), inset),
                size = Size(segmentWidth, innerHeight),
                cornerRadius = CornerRadius(segmentWidth * 0.3f),
            )
        }
    }
}

// ---- Previews: unknown, 100 %, 60 % and 20 %, light and dark ----

@Composable
private fun GaugePreview(battery: RemoteBattery?) {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme) {
        Surface {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BatteryGauge(percent = battery?.percent)
                // Same split as BatteryRow on the screen: the measured value is formatted, the case without a
                // measurement is wording and comes from the resources.
                Text(
                    text = battery?.let { RemoteStatusText.batteryValue(it) }
                        ?: stringResource(R.string.battery_unknown),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

private val PREVIEW_FULL = RemoteBattery(percent = 100, millivolts = 4_180, atEpochMs = 1_789_209_296_000L)
private val PREVIEW_MIDDLE = RemoteBattery(percent = 60, millivolts = 3_736, atEpochMs = 1_789_209_296_000L)
private val PREVIEW_LOW = RemoteBattery(percent = 20, millivolts = 3_560, atEpochMs = 1_789_209_296_000L)

@Preview(name = "Akku unbekannt – hell", showBackground = true)
@Composable
private fun PreviewGaugeUnknownLight() = GaugePreview(null)

@Preview(name = "Akku unbekannt – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewGaugeUnknownDark() = GaugePreview(null)

@Preview(name = "Akku 100 % – hell", showBackground = true)
@Composable
private fun PreviewGaugeFullLight() = GaugePreview(PREVIEW_FULL)

@Preview(name = "Akku 100 % – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewGaugeFullDark() = GaugePreview(PREVIEW_FULL)

@Preview(name = "Akku 60 % – hell", showBackground = true)
@Composable
private fun PreviewGaugeMiddleLight() = GaugePreview(PREVIEW_MIDDLE)

@Preview(name = "Akku 60 % – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewGaugeMiddleDark() = GaugePreview(PREVIEW_MIDDLE)

@Preview(name = "Akku 20 % – hell", showBackground = true)
@Composable
private fun PreviewGaugeLowLight() = GaugePreview(PREVIEW_LOW)

@Preview(name = "Akku 20 % – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewGaugeLowDark() = GaugePreview(PREVIEW_LOW)
