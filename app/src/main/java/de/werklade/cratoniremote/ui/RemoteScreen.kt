package de.werklade.cratoniremote.ui

import android.Manifest
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.werklade.cratoniremote.R
import de.werklade.cratoniremote.action.ACTION_COMMANDS
import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.FailureReason
import de.werklade.cratoniremote.ble.GattStep
import de.werklade.cratoniremote.ble.RemoteBattery
import de.werklade.cratoniremote.data.CONFIRMATION_TIMEOUT_MS
import de.werklade.cratoniremote.data.ConfirmationOutcome
import de.werklade.cratoniremote.data.LastCommand
import de.werklade.cratoniremote.data.PendingConfirmation
import de.werklade.cratoniremote.data.RemoteState
import de.werklade.cratoniremote.data.RemoteStatusText
import de.werklade.cratoniremote.helmet.HelmetConnectionState
import de.werklade.cratoniremote.helmet.HelmetFailure
import de.werklade.cratoniremote.helmet.HelmetLights
import de.werklade.cratoniremote.helmet.RearLight
import de.werklade.cratoniremote.protocol.RemoteCommand

/**
 * Status and setup screen (docs/architektur.md §10, step 6).
 *
 * Purpose, deliberately narrow: see at a glance whether the remote is working, and set it up or remove it.
 * Built for a cyclist glancing at the phone, often outdoors — no decoration, no animation without function.
 *
 * Stateless regarding the domain: everything shown is derived from [remoteState] and the flags below; the only
 * local state is the transient visibility of the "forget" confirmation dialog. Every visible text is a string
 * resource; [RemoteStatusText] is intentionally not reused here (it is the wording of the foreground-service
 * notification, not localised resources) except for the formatting helpers [RemoteStatusText.timestamp],
 * [RemoteStatusText.showsDate], [RemoteStatusText.shortTimestamp] and [RemoteStatusText.batteryValue] — the last one
 * because its locale-fixed number format is only worth having in one unit tested place.
 *
 * @param missingPermissionNames not yet granted runtime permissions as returned by the framework (e.g.
 *   `android.permission.BLUETOOTH_CONNECT`), empty if all are granted; [permissionLabel] turns them into words
 * @param notificationPermissionMissing whether `POST_NOTIFICATIONS` specifically is missing (its own hint,
 *   because the running connection's status then has no visible notification)
 * @param callPermissionMissing whether `ANSWER_PHONE_CALLS` specifically is missing (its own hint, because
 *   the consequence is narrow and worth naming: the remote's call button does nothing, the rest is unaffected)
 * @param setupFailed whether the last attempt to set up was refused by the system; a dialog the user cancelled
 *   is not a failure and must not set this
 * @param showBatteryOptimizationHint whether to show the Samsung "never sleep" hint (caller decides relevance,
 *   e.g. manufacturer and whether a remote is set up)
 * @param showLocationHint whether to show the "location switch must be on to set up" hint
 * @param assistantTarget which app the walkie-talkie button hands over to, `null` while the caller is still
 *   asking the system (the question needs package manager work, which must not happen on the main thread)
 * @param onCancelConfirmation called when the user cancels the waiting for the confirmation press; the caller
 *   takes the association back
 * @param onOpenAssistantSettings called for the button next to [assistantTarget]; returns `false` if no system
 *   page for the choice could be opened, which is the only case in which this screen says anything about it
 * @param confirmationRemainingSeconds whole seconds left of
 *   [de.werklade.cratoniremote.data.RemoteState.pendingConfirmation], counted by the caller (the countdown
 *   runs on `SystemClock.elapsedRealtime`, which this screen deliberately does not read itself)
 */
@Composable
fun RemoteScreen(
    bluetoothEnabled: Boolean,
    remoteConfigured: Boolean,
    remoteState: RemoteState,
    missingPermissionNames: List<String>,
    notificationPermissionMissing: Boolean,
    callPermissionMissing: Boolean,
    showBatteryOptimizationHint: Boolean,
    showLocationHint: Boolean,
    assistantTarget: AssistantTarget?,
    onRequestPermissions: () -> Unit,
    onSetUp: () -> Unit,
    onForget: () -> Unit,
    onCancelConfirmation: () -> Unit,
    onOpenAssistantSettings: () -> Boolean,
    modifier: Modifier = Modifier,
    // After the modifier, as the Compose API guidelines require of the first optional parameter.
    setupFailed: Boolean = false,
    confirmationRemainingSeconds: Int = 0,
) {
    var showForgetConfirmation by rememberSaveable { mutableStateOf(false) }
    val pendingConfirmation = remoteState.pendingConfirmation

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)

        if (pendingConfirmation != null) {
            // The association exists but is not the user's remote yet, so neither "Einrichten" nor
            // "Remote vergessen" applies: the only two things to do are pressing a button on the remote and
            // cancelling. Placed first because nothing else on the screen matters until this is settled.
            ConfirmationCard(
                label = pendingConfirmation.label,
                remainingSeconds = confirmationRemainingSeconds,
                onCancel = onCancelConfirmation,
            )
        }

        StatusOverviewCard(
            bluetoothEnabled = bluetoothEnabled,
            remoteConfigured = remoteConfigured,
            state = remoteState,
            assistantTarget = assistantTarget,
            onOpenAssistantSettings = onOpenAssistantSettings,
        )

        if (missingPermissionNames.isNotEmpty()) {
            val names = missingPermissionNames.map { permissionLabel(it) }.joinToString(", ")
            Text(text = stringResource(R.string.permissions_missing, names))
            Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.action_request_permissions))
            }
        }

        // Neither "Einrichten" nor "Remote vergessen" is offered while confirming, see the card above.
        if (pendingConfirmation == null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!remoteConfigured) {
                    Button(onClick = onSetUp, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.action_set_up))
                    }
                    if (setupFailed) {
                        Text(
                            text = stringResource(R.string.setup_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { showForgetConfirmation = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(text = stringResource(R.string.action_forget)) }
                }
                // Tied to the outcome itself, not to "no remote set up": the association is taken back in the
                // background, so for a moment there can still be one — and a failed confirmation of a second
                // remote leaves a set up one all along. Both cases need the explanation.
                ConfirmationOutcomeText(remoteState.lastConfirmation)
            }
        }

        HintsSection(
            notificationPermissionMissing = notificationPermissionMissing,
            callPermissionMissing = callPermissionMissing,
            showBatteryOptimizationHint = showBatteryOptimizationHint,
            showLocationHint = showLocationHint,
            showChooseRemoteHint = !remoteConfigured && pendingConfirmation == null,
        )
    }

    if (showForgetConfirmation) {
        AlertDialog(
            onDismissRequest = { showForgetConfirmation = false },
            title = { Text(text = stringResource(R.string.forget_confirm_title)) },
            text = { Text(text = stringResource(R.string.forget_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForgetConfirmation = false
                        onForget()
                    },
                ) { Text(text = stringResource(R.string.forget_confirm_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showForgetConfirmation = false }) {
                    Text(text = stringResource(R.string.forget_confirm_cancel))
                }
            },
        )
    }
}

/** Seconds left from which the card turns to the error roles; the number and the bar say the same in words. */
private const val CONFIRMATION_URGENT_SECONDS = 10

private const val MILLIS_PER_SECOND = 1_000L

/**
 * Waiting for the confirmation press on the chosen remote: what to do, on which remote, how long there is left
 * and how to say "wrong one" (by doing nothing, or by cancelling).
 *
 * It is the only thing that matters while it is shown, hence the coloured container (the same device as
 * [StatusOverviewCard]: container role marks the state) and its place above the status card. The countdown comes
 * from the caller ([RemoteScreen]'s `confirmationRemainingSeconds`), so this stays free of timers and of clock
 * readings. The bar jumps once a second without animation: a smooth sweep would be motion for its own sake.
 *
 * The container turns to `errorContainer` for the last [CONFIRMATION_URGENT_SECONDS] seconds; colour is never
 * the only carrier — the remaining seconds are text, and the bar shrinks.
 *
 * Screen reader: the remaining time is a polite live region, but its description only changes in steps
 * ([announcedSeconds]), so it is spoken four times instead of every second. The bar is hidden from accessibility
 * since it would be read as a percentage next to the seconds.
 */
@Composable
private fun ConfirmationCard(
    label: String,
    remainingSeconds: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val urgent = remainingSeconds <= CONFIRMATION_URGENT_SECONDS
    val containerColor = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = contentColorFor(containerColor)
    val totalSeconds = (CONFIRMATION_TIMEOUT_MS / MILLIS_PER_SECOND).toInt()
    val message = stringResource(R.string.confirmation_message, label)
    val announcedRemaining =
        stringResource(R.string.confirmation_remaining, announcedSeconds(remainingSeconds, totalSeconds))
    // The name of the remote is what the user compares with the other remotes on the table: make it stand out.
    val highlightedMessage = remember(message, label) {
        buildAnnotatedString {
            val start = if (label.isEmpty()) -1 else message.indexOf(label)
            if (start < 0) {
                append(message)
            } else {
                append(message.substring(0, start))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
                append(message.substring(start + label.length))
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.confirmation_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            // Spoken once when the card appears: it is the instruction, and TalkBack users have no card
            // to glance at. Its text does not change while the card is up, so it cannot repeat itself.
            Text(
                text = highlightedMessage,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.confirmation_remaining, remainingSeconds),
                    // Equal-width digits, otherwise the line wobbles with every second.
                    style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                    // clearAndSetSemantics, not semantics: the text itself changes every second, and a
                    // live region is announced whenever any of its values change — the description alone has
                    // to be what the screen reader sees, otherwise it speaks every single second.
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = announcedRemaining
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                LinearProgressIndicator(
                    progress = { (remainingSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                    color = contentColor,
                    trackColor = contentColor.copy(alpha = 0.24f),
                )
            }
            Text(
                text = stringResource(R.string.confirmation_hint_wrong_remote),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
                border = BorderStroke(1.dp, contentColor),
            ) {
                Text(text = stringResource(R.string.action_cancel_confirmation))
            }
        }
    }
}

/**
 * The few points at which the countdown is spoken, ascending, derived from the timeout instead of written out:
 * half the urgent mark, the urgent mark ([CONFIRMATION_URGENT_SECONDS]), two thirds of the time and the full
 * time. With the agreed 30 s that is 5, 10, 20, 30.
 */
private fun announcedSteps(totalSeconds: Int): List<Int> = listOf(
    CONFIRMATION_URGENT_SECONDS / 2,
    CONFIRMATION_URGENT_SECONDS,
    totalSeconds * 2 / 3,
    totalSeconds,
).filter { it > 0 }.distinct().sorted()

/**
 * The value a screen reader gets for the countdown: the next [announcedSteps] value at or above
 * [remainingSeconds], so the live region changes — and is spoken — only at those few points, each exactly
 * when the real value passes it. Zero stays zero: the time is up and that is worth saying.
 */
private fun announcedSeconds(remainingSeconds: Int, totalSeconds: Int): Int {
    if (remainingSeconds <= 0) return 0
    val steps = announcedSteps(totalSeconds)
    return steps.firstOrNull { it >= remainingSeconds } ?: totalSeconds
}

/**
 * Why the last confirmation did not lead to a set up remote. Nothing is shown after a successful one: the
 * status card then speaks for itself.
 */
@Composable
private fun ConfirmationOutcomeText(outcome: ConfirmationOutcome?, modifier: Modifier = Modifier) {
    val text = when (outcome) {
        null, ConfirmationOutcome.CONFIRMED -> return
        ConfirmationOutcome.TIMED_OUT -> stringResource(R.string.confirmation_failed_timeout)
        ConfirmationOutcome.CANCELLED -> stringResource(R.string.confirmation_cancelled)
        ConfirmationOutcome.BLUETOOTH_OFF -> stringResource(R.string.confirmation_failed_bluetooth_off)
        ConfirmationOutcome.FAILED -> stringResource(R.string.confirmation_failed_internal)
    }
    // A status message: it appears when the card above disappears, so it is announced instead of the user
    // having to find it.
    Text(
        text = text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** The seven status lines in one card; its container colour marks the two states worth noticing at a glance. */
@Composable
private fun StatusOverviewCard(
    bluetoothEnabled: Boolean,
    remoteConfigured: Boolean,
    state: RemoteState,
    assistantTarget: AssistantTarget?,
    onOpenAssistantSettings: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = statusContainerColor(remoteConfigured, state)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColorFor(containerColor)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusRow(
                label = stringResource(R.string.label_bluetooth_status),
                value = stringResource(if (bluetoothEnabled) R.string.bluetooth_on else R.string.bluetooth_off),
            )
            StatusRow(
                label = stringResource(R.string.label_remote_status),
                value = remoteStatusText(remoteConfigured, state),
                announceChanges = true,
            )
            BatteryRow(state.battery)
            StatusRow(label = stringResource(R.string.label_helmet), value = helmetStatusText(state.helmet))
            StatusRow(label = stringResource(R.string.label_lights), value = lightsText(state.lights))
            AssistantRow(target = assistantTarget, onOpenSettings = onOpenAssistantSettings)
            LastCommandRow(state.lastCommand)
        }
    }
}

/**
 * Errors and the healthy "connected" state get their own colour; every other state stays neutral.
 *
 * Without a set up remote the connection state says nothing: a [BleConnectionState.Failed] left over from an
 * earlier session would colour the card red next to the line "Nicht eingerichtet".
 */
@Composable
private fun statusContainerColor(remoteConfigured: Boolean, state: RemoteState): Color = when {
    !remoteConfigured -> MaterialTheme.colorScheme.surfaceVariant
    state.connection is BleConnectionState.Failed -> MaterialTheme.colorScheme.errorContainer
    !state.waitingForBluetooth && state.connection == BleConnectionState.Connected ->
        MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

/**
 * Battery level as a drawn symbol plus the value in words, e.g. "60 % (3,74 V)", and below it when the value was
 * measured — the remote is read every few minutes ([de.werklade.cratoniremote.ble.CratoniBleManager]), so the
 * time of the measurement is what makes the number trustworthy.
 *
 * The measurement can be many hours old: a connection survives midnight, and the poll stands still in deep
 * sleep. From half a day on ([RemoteStatusText.shortTimestamp]) the caption therefore carries the date as well,
 * like [LastCommandRow]. "Now" is read at composition, the same reasoning as there.
 *
 * The value text comes from [RemoteStatusText.batteryValue] instead of a string resource, see there.
 */
@Composable
private fun BatteryRow(battery: RemoteBattery?, modifier: Modifier = Modifier) {
    StatusRow(
        label = stringResource(R.string.label_battery),
        value = RemoteStatusText.batteryValue(battery),
        modifier = modifier,
        caption = battery?.let {
            val now = System.currentTimeMillis()
            val format = if (RemoteStatusText.showsDate(it.atEpochMs, now)) {
                R.string.battery_measured_at_date
            } else {
                R.string.battery_measured_at
            }
            stringResource(format, RemoteStatusText.shortTimestamp(it.atEpochMs, now))
        },
        leading = { BatteryGauge(percent = battery?.percent) },
    )
}

/**
 * Which app the walkie-talkie button hands over to, plus the way into the system page where that is decided.
 *
 * Right above "Letzter Befehl" on purpose: that line often reads "Sprachassistent" and this one explains what
 * happened then — or why nothing did. The app's name is not a status of this app, so the row stays neutral in
 * every case; `null` means the caller is still asking the system.
 *
 * The button is always there: without a default app it is the way to set one, with one it is the way to change
 * it. Only when none of the system pages exists on this device does a quiet caption say so — a dialog or a
 * toast for a settings shortcut would be out of proportion. That state lives here because it is nothing but the
 * result of the last tap.
 */
@Composable
private fun AssistantRow(
    target: AssistantTarget?,
    onOpenSettings: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var settingsMissing by remember { mutableStateOf(false) }
    // The visible button text is short on purpose; out of context, between seven status lines, "Einstellungen
    // öffnen" says nothing about which settings, so a screen reader gets the long version.
    val openSettingsDescription = stringResource(R.string.action_assistant_settings_description)
    val value = when (target) {
        null -> stringResource(R.string.assistant_pending)
        AssistantTarget.None -> stringResource(R.string.assistant_none)
        AssistantTarget.AskedOnPress -> stringResource(R.string.assistant_asked_on_press)
        is AssistantTarget.App -> target.label
    }
    // The card's content colour, so the outlined button carries the container role of the card instead of the
    // theme's primary, which does not hold against primaryContainer or errorContainer.
    val contentColor = LocalContentColor.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusRow(
            label = stringResource(R.string.label_assistant),
            value = value,
            caption = if (settingsMissing) stringResource(R.string.assistant_settings_missing) else null,
            // A live region because of the caption: it is the answer to the tap right below it and would
            // otherwise go unnoticed. The value itself changes once, when the first answer replaces
            // "Wird ermittelt …", which is worth hearing too.
            announceChanges = true,
            // The name comes from another app; assistantLabel already shortens it, this keeps even a tidied
            // long name from pushing the card apart.
            valueMaxLines = 2,
        )
        OutlinedButton(
            onClick = { settingsMissing = !onOpenSettings() },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = openSettingsDescription },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
            border = BorderStroke(1.dp, contentColor),
        ) {
            Text(text = stringResource(R.string.action_assistant_settings))
        }
    }
}

/**
 * The last command survives the service being stopped, so it can be hours old. From half a day on
 * ([RemoteStatusText.showsDate]) the timestamp carries the date, otherwise a command from yesterday evening
 * reads like one from a few minutes ago.
 *
 * "Now" is read at composition; the row recomposes whenever the command changes, which is exactly when the
 * distinction can change from the app's point of view.
 */
@Composable
private fun LastCommandRow(last: LastCommand?, modifier: Modifier = Modifier) {
    val value = if (last != null) {
        val now = System.currentTimeMillis()
        val format = if (RemoteStatusText.showsDate(last.atEpochMs, now)) {
            R.string.last_command_date_time
        } else {
            R.string.last_command_time
        }
        commandText(last.command) + " " + stringResource(format, RemoteStatusText.timestamp(last.atEpochMs, now))
    } else {
        stringResource(R.string.last_command_none)
    }
    val caption = if (last != null && last.command !in ACTION_COMMANDS) {
        stringResource(R.string.last_command_display_only_note)
    } else {
        null
    }
    // Deliberately not a live region: a spoken interruption for every button press on the remote — including
    // the second in the timestamp — drowns out the one line that is worth announcing, the connection state.
    StatusRow(
        label = stringResource(R.string.label_last_command),
        value = value,
        modifier = modifier,
        caption = caption,
    )
}

/**
 * One label/value pair. [announceChanges] marks it as a live region so screen readers notice updates without
 * the user having to move focus there; only the remote's status line uses it.
 *
 * @param valueMaxLines how many lines the value may take; unlimited by default, because every value this app
 *   produces itself is short. Only the assistant row limits it — its value is another app's name.
 * @param leading optional symbol in front of the value, aligned with it; only the battery row uses it. The row is
 *   a merged semantics node, so a decorative symbol adds nothing to what a screen reader reads out.
 */
@Composable
private fun StatusRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    announceChanges: Boolean = false,
    valueMaxLines: Int = Int.MAX_VALUE,
    leading: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            if (announceChanges) liveRegion = LiveRegionMode.Polite
        },
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        if (leading == null) {
            ValueText(value = value, maxLines = valueMaxLines)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                leading()
                ValueText(value = value, maxLines = valueMaxLines)
            }
        }
        if (caption != null) {
            Text(text = caption, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The value of a [StatusRow]; cut with an ellipsis once [maxLines] is reached instead of growing the card. */
@Composable
private fun ValueText(value: String, maxLines: Int) {
    Text(
        text = value,
        style = MaterialTheme.typography.titleMedium,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun HintsSection(
    notificationPermissionMissing: Boolean,
    callPermissionMissing: Boolean,
    showBatteryOptimizationHint: Boolean,
    showLocationHint: Boolean,
    showChooseRemoteHint: Boolean,
    modifier: Modifier = Modifier,
) {
    val hints = mutableListOf<String>()
    if (notificationPermissionMissing) hints.add(stringResource(R.string.hint_notification_permission_missing))
    if (callPermissionMissing) hints.add(stringResource(R.string.hint_call_permission_missing))
    if (showChooseRemoteHint) hints.add(stringResource(R.string.hint_choose_your_remote))
    if (showLocationHint) hints.add(stringResource(R.string.hint_location_required))
    if (showBatteryOptimizationHint) hints.add(stringResource(R.string.hint_battery_optimization))
    if (hints.isEmpty()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        hints.forEach { hint ->
            Text(text = hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * State of the SPP connection to the helmet (docs/architektur.md §13).
 *
 * [HelmetConnectionState.NotFound] gets its own wording: no paired helmet whose name starts with
 * `SMARTRIDE`, or more than one — nothing a reconnect can fix, unlike a helmet that is merely switched off
 * ([HelmetFailure.CONNECT]). A failure is not shown as an error either, because it says nothing more alarming
 * than "the helmet is not there right now"; the lights simply do not react (no sound, by decision).
 */
@Composable
private fun helmetStatusText(state: HelmetConnectionState): String = when (state) {
    HelmetConnectionState.Disconnected -> stringResource(R.string.helmet_status_disconnected)
    HelmetConnectionState.NotFound -> stringResource(R.string.helmet_status_not_found)
    HelmetConnectionState.Connecting -> stringResource(R.string.helmet_status_connecting)
    HelmetConnectionState.Connected -> stringResource(R.string.helmet_status_connected)
    is HelmetConnectionState.Failed ->
        stringResource(R.string.helmet_status_error, helmetFailureText(state.reason))
}

@Composable
private fun helmetFailureText(reason: HelmetFailure): String = when (reason) {
    HelmetFailure.BLUETOOTH_UNAVAILABLE -> stringResource(R.string.helmet_failure_bluetooth_unavailable)
    HelmetFailure.MISSING_PERMISSION -> stringResource(R.string.helmet_failure_missing_permission)
    HelmetFailure.CONNECT -> stringResource(R.string.helmet_failure_connect)
    HelmetFailure.SEND -> stringResource(R.string.helmet_failure_send)
}

/**
 * What the app believes the helmet's lights are doing, e.g. "Blinker links an, Rücklicht blinkt", or "Aus".
 *
 * All three lights are independent, so every one that is on is named; nothing switches a turn signal off by
 * itself (that is the behaviour of the manufacturer app), which makes this line the only reminder that one is
 * still blinking.
 */
@Composable
private fun lightsText(lights: HelmetLights): String {
    val parts = buildList {
        if (lights.left) add(stringResource(R.string.light_turn_left_on))
        if (lights.right) add(stringResource(R.string.light_turn_right_on))
        when (lights.rear) {
            RearLight.OFF -> Unit
            RearLight.SOLID -> add(stringResource(R.string.light_rear_solid))
            RearLight.BLINKING -> add(stringResource(R.string.light_rear_blinking))
        }
    }
    return if (parts.isEmpty()) {
        stringResource(R.string.lights_off)
    } else {
        parts.joinToString(stringResource(R.string.lights_separator))
    }
}

/**
 * Same distinction as [RemoteStatusText.remoteLine], here from string resources: with a remote set up but no
 * running service the system has simply not reported it as present yet. "Getrennt" would read like a fault the
 * user has to fix, while nothing is wrong and the connection comes back by itself.
 */
@Composable
private fun remoteStatusText(remoteConfigured: Boolean, state: RemoteState): String = when {
    !remoteConfigured -> stringResource(R.string.remote_status_not_configured)
    state.pendingConfirmation != null -> stringResource(R.string.remote_status_confirming)
    state.waitingForBluetooth -> stringResource(R.string.remote_status_waiting_for_bluetooth)
    !state.serviceRunning && state.connection == BleConnectionState.Disconnected ->
        stringResource(R.string.remote_status_out_of_range)
    else -> connectionStateText(state.connection)
}

@Composable
private fun connectionStateText(connection: BleConnectionState): String = when (connection) {
    BleConnectionState.Disconnected -> stringResource(R.string.remote_status_disconnected)
    BleConnectionState.Connecting -> stringResource(R.string.remote_status_connecting)
    BleConnectionState.DiscoveringServices -> stringResource(R.string.remote_status_discovering)
    BleConnectionState.EnablingNotifications -> stringResource(R.string.remote_status_enabling_notifications)
    BleConnectionState.Connected -> stringResource(R.string.remote_status_connected)
    is BleConnectionState.Reconnecting -> stringResource(
        R.string.remote_status_reconnecting,
        connection.attempt,
        connection.maxAttempts,
        (connection.delayMs / 1000).toInt(),
    )
    is BleConnectionState.Failed -> stringResource(R.string.remote_status_error, failureReasonText(connection.reason))
}

@Composable
private fun failureReasonText(reason: FailureReason): String = when (reason) {
    FailureReason.BluetoothAdapterMissing -> stringResource(R.string.failure_bluetooth_adapter_missing)
    FailureReason.BluetoothDisabled -> stringResource(R.string.failure_bluetooth_disabled)
    FailureReason.MissingPermission -> stringResource(R.string.failure_missing_permission)
    is FailureReason.GattError -> stringResource(R.string.failure_gatt_error, gattStepText(reason.step))
    is FailureReason.ConnectionLost -> stringResource(R.string.failure_connection_lost)
    is FailureReason.OperationNotStarted -> stringResource(R.string.failure_operation_not_started, gattStepText(reason.step))
    is FailureReason.Timeout -> stringResource(R.string.failure_timeout, gattStepText(reason.step))
    is FailureReason.DeviceUnsuitable -> stringResource(R.string.failure_device_unsuitable, reason.missing)
}

@Composable
private fun gattStepText(step: GattStep): String = when (step) {
    GattStep.CONNECT -> stringResource(R.string.gatt_step_connect)
    GattStep.DISCOVER_SERVICES -> stringResource(R.string.gatt_step_discover_services)
    GattStep.ENABLE_NOTIFICATIONS -> stringResource(R.string.gatt_step_enable_notifications)
}

/**
 * The one place that turns a permission name into words the user recognises from the system dialogs.
 *
 * The fallback keeps an unmapped permission visible instead of silently dropping it; it can only appear if
 * `MainActivity.REQUIRED_PERMISSIONS` grows without a string being added here.
 */
@Composable
private fun permissionLabel(permission: String): String = when (permission) {
    Manifest.permission.BLUETOOTH_CONNECT -> stringResource(R.string.permission_bluetooth_connect)
    Manifest.permission.POST_NOTIFICATIONS -> stringResource(R.string.permission_post_notifications)
    Manifest.permission.ANSWER_PHONE_CALLS -> stringResource(R.string.permission_answer_phone_calls)
    else -> permission.substringAfterLast('.')
}

@Composable
private fun commandText(command: RemoteCommand): String = when (command) {
    RemoteCommand.PLAY_PAUSE -> stringResource(R.string.command_play_pause)
    RemoteCommand.NEXT -> stringResource(R.string.command_next)
    RemoteCommand.PREVIOUS -> stringResource(R.string.command_previous)
    RemoteCommand.VOLUME_UP -> stringResource(R.string.command_volume_up)
    RemoteCommand.VOLUME_DOWN -> stringResource(R.string.command_volume_down)
    RemoteCommand.CALL -> stringResource(R.string.command_call)
    RemoteCommand.WALKIE_TALKIE -> stringResource(R.string.command_walkie_talkie)
    RemoteCommand.TURN_LEFT -> stringResource(R.string.command_turn_left)
    RemoteCommand.TURN_RIGHT -> stringResource(R.string.command_turn_right)
    RemoteCommand.REAR_LIGHT -> stringResource(R.string.command_rear_light)
    RemoteCommand.UNKNOWN -> stringResource(R.string.command_unknown)
}

// ---- Previews: the four states that matter most, light and dark (docs/architektur.md §10, step 6) ----

private val PREVIEW_NOT_CONFIGURED = RemoteState()

private val PREVIEW_CONNECTED = RemoteState(
    serviceRunning = true,
    connection = BleConnectionState.Connected,
    battery = RemoteBattery(percent = 60, millivolts = 3_736, atEpochMs = 1_726_000_000_000L),
    helmet = HelmetConnectionState.Connected,
    lights = HelmetLights(left = true, rear = RearLight.BLINKING),
    lastCommand = LastCommand(RemoteCommand.REAR_LIGHT, 1_726_000_000_000L),
)

private val PREVIEW_ERROR = RemoteState(
    serviceRunning = true,
    connection = BleConnectionState.Failed(FailureReason.GattError(GattStep.DISCOVER_SERVICES, 133)),
)

private val PREVIEW_WAITING_FOR_BLUETOOTH = RemoteState(
    serviceRunning = true,
    waitingForBluetooth = true,
)

// The association exists while confirming, so the screen counts as set up; the deadline is irrelevant here, the
// remaining seconds are handed to the screen separately like in the app.
private val PREVIEW_CONFIRMING = RemoteState(
    serviceRunning = true,
    connection = BleConnectionState.Connecting,
    pendingConfirmation = PendingConfirmation(
        associationId = 1,
        label = "Remote control-3D2C",
        deadlineElapsedMs = 0L,
    ),
)

// After the timeout the association is gone again: not set up, with the reason below the button.
private val PREVIEW_CONFIRMATION_TIMED_OUT = RemoteState(lastConfirmation = ConfirmationOutcome.TIMED_OUT)

// The app name for the assistant row in the previews below; the other three answers the system can give have
// previews of their own at the end of this file.
private val PREVIEW_ASSISTANT_APP = AssistantTarget.App("Google")

@Composable
private fun PreviewScreen(
    remoteConfigured: Boolean,
    state: RemoteState,
    confirmationRemainingSeconds: Int = 0,
    assistantTarget: AssistantTarget? = PREVIEW_ASSISTANT_APP,
) {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme) {
        RemoteScreen(
            bluetoothEnabled = true,
            remoteConfigured = remoteConfigured,
            remoteState = state,
            missingPermissionNames = emptyList(),
            notificationPermissionMissing = false,
            callPermissionMissing = false,
            showBatteryOptimizationHint = false,
            showLocationHint = false,
            assistantTarget = assistantTarget,
            onRequestPermissions = {},
            onSetUp = {},
            onForget = {},
            onCancelConfirmation = {},
            onOpenAssistantSettings = { true },
            confirmationRemainingSeconds = confirmationRemainingSeconds,
        )
    }
}

@Preview(name = "Nicht eingerichtet – hell", showBackground = true)
@Composable
private fun PreviewNotConfiguredLight() = PreviewScreen(remoteConfigured = false, state = PREVIEW_NOT_CONFIGURED)

@Preview(name = "Nicht eingerichtet – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewNotConfiguredDark() = PreviewScreen(remoteConfigured = false, state = PREVIEW_NOT_CONFIGURED)

@Preview(name = "Verbunden – hell", showBackground = true)
@Composable
private fun PreviewConnectedLight() = PreviewScreen(remoteConfigured = true, state = PREVIEW_CONNECTED)

@Preview(name = "Verbunden – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewConnectedDark() = PreviewScreen(remoteConfigured = true, state = PREVIEW_CONNECTED)

@Preview(name = "Fehler – hell", showBackground = true)
@Composable
private fun PreviewErrorLight() = PreviewScreen(remoteConfigured = true, state = PREVIEW_ERROR)

@Preview(name = "Fehler – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewErrorDark() = PreviewScreen(remoteConfigured = true, state = PREVIEW_ERROR)

@Preview(name = "Wartet auf Bluetooth – hell", showBackground = true)
@Composable
private fun PreviewWaitingLight() = PreviewScreen(remoteConfigured = true, state = PREVIEW_WAITING_FOR_BLUETOOTH)

@Preview(name = "Wartet auf Bluetooth – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewWaitingDark() = PreviewScreen(remoteConfigured = true, state = PREVIEW_WAITING_FOR_BLUETOOTH)

@Preview(name = "Bestätigung läuft, viel Zeit – hell", showBackground = true)
@Composable
private fun PreviewConfirmingPlentyLight() =
    PreviewScreen(remoteConfigured = true, state = PREVIEW_CONFIRMING, confirmationRemainingSeconds = 25)

@Preview(name = "Bestätigung läuft, viel Zeit – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewConfirmingPlentyDark() =
    PreviewScreen(remoteConfigured = true, state = PREVIEW_CONFIRMING, confirmationRemainingSeconds = 25)

@Preview(name = "Bestätigung läuft, wenig Zeit – hell", showBackground = true)
@Composable
private fun PreviewConfirmingLowLight() =
    PreviewScreen(remoteConfigured = true, state = PREVIEW_CONFIRMING, confirmationRemainingSeconds = 6)

@Preview(name = "Bestätigung läuft, wenig Zeit – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewConfirmingLowDark() =
    PreviewScreen(remoteConfigured = true, state = PREVIEW_CONFIRMING, confirmationRemainingSeconds = 6)

@Preview(name = "Bestätigung fehlgeschlagen (Timeout) – hell", showBackground = true)
@Composable
private fun PreviewConfirmationTimedOutLight() =
    PreviewScreen(remoteConfigured = false, state = PREVIEW_CONFIRMATION_TIMED_OUT)

@Preview(name = "Bestätigung fehlgeschlagen (Timeout) – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewConfirmationTimedOutDark() =
    PreviewScreen(remoteConfigured = false, state = PREVIEW_CONFIRMATION_TIMED_OUT)

// The other three states of the assistant row; the app name is in every preview above, "Verbunden" included.

@Preview(name = "Assistent nicht festgelegt – hell", showBackground = true)
@Composable
private fun PreviewAssistantNoneLight() =
    PreviewScreen(remoteConfigured = true, state = PREVIEW_CONNECTED, assistantTarget = AssistantTarget.None)

@Preview(name = "Assistent nicht festgelegt – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewAssistantNoneDark() =
    PreviewScreen(remoteConfigured = true, state = PREVIEW_CONNECTED, assistantTarget = AssistantTarget.None)

@Preview(name = "Assistent wird abgefragt – hell", showBackground = true)
@Composable
private fun PreviewAssistantAskedLight() =
    PreviewScreen(remoteConfigured = true, state = PREVIEW_CONNECTED, assistantTarget = AssistantTarget.AskedOnPress)

@Preview(name = "Assistent wird abgefragt – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewAssistantAskedDark() =
    PreviewScreen(remoteConfigured = true, state = PREVIEW_CONNECTED, assistantTarget = AssistantTarget.AskedOnPress)

@Preview(name = "Assistent wird ermittelt – hell", showBackground = true)
@Composable
private fun PreviewAssistantPendingLight() =
    PreviewScreen(remoteConfigured = true, state = PREVIEW_CONNECTED, assistantTarget = null)

@Preview(name = "Assistent wird ermittelt – dunkel", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewAssistantPendingDark() =
    PreviewScreen(remoteConfigured = true, state = PREVIEW_CONNECTED, assistantTarget = null)
