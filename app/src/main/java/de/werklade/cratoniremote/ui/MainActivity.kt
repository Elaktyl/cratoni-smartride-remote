package de.werklade.cratoniremote.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.werklade.cratoniremote.R
import de.werklade.cratoniremote.ble.CratoniBleManager
import de.werklade.cratoniremote.companion.CompanionSetup
import de.werklade.cratoniremote.data.CONFIRMATION_TIMEOUT_MS
import de.werklade.cratoniremote.data.ConfirmationOutcome
import de.werklade.cratoniremote.data.PendingConfirmation
import de.werklade.cratoniremote.data.RemoteStatus
import de.werklade.cratoniremote.service.RemoteConnectionService
import de.werklade.cratoniremote.service.shouldStartConnectionService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host of the status and setup screen ([RemoteScreen], docs/architektur.md §10, step 6).
 *
 * An Activity is required for two things: the companion association dialog is started through an
 * [android.content.IntentSender], and the runtime permissions `BLUETOOTH_CONNECT` and `POST_NOTIFICATIONS`
 * can only be requested from one. The remote itself works without this Activity — the connection lives in
 * [de.werklade.cratoniremote.service.RemoteConnectionService].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Follows the system setting; themes.xml/values-night keep the platform theme (status/navigation
            // bar icon contrast via enableEdgeToEdge()) in sync with this Compose colour scheme.
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RemoteRoute(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * Connects [RemoteStatus], [CompanionSetup] and the runtime permissions to [RemoteScreen].
 *
 * State observation uses `collectAsStateWithLifecycle`, so collection stops below STARTED instead of running on
 * an invisible screen. `lifecycle-runtime-compose` is declared in the version catalog for it, together with the
 * `LocalLifecycleOwner`, `Lifecycle` and `LifecycleEventObserver` used below for the `ON_RESUME` refresh — they
 * were previously only on the compile classpath transitively through `activity-compose`.
 *
 * No `RemoteViewModel`: this composable only reads state ([RemoteStatus.state], the association, the runtime
 * permissions, Bluetooth and location) and triggers the actions of the screen; it holds no business logic of
 * its own — the pairing rules live in [de.werklade.cratoniremote.companion.PairingReconciler] and
 * [PendingConfirmation], both pure Kotlin and unit tested. docs/architektur.md §8 names a ViewModel as
 * foreseen but optional under exactly that condition.
 *
 * The confirmation of a chosen remote is shown and counted here, but not guaranteed here: this composition
 * ends with the Activity, and a chosen remote must not keep waiting after that, so
 * [RemoteConnectionService] enforces the same deadline where the connection is. What is persisted follows the
 * same idea — the pending marker and the "forget"/"take back" work run in [PAIRING_SCOPE], which outlives the
 * composition, and the confirmation itself is written by the service that received the press
 * ([de.werklade.cratoniremote.service.persistConfirmation]) and, idempotently, here.
 */
@Composable
private fun RemoteRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val setup = remember(context) { CompanionSetup(context) }
    val state by RemoteStatus.state.collectAsStateWithLifecycle()
    val bluetoothEnabled = rememberBluetoothEnabled()
    val locationEnabled = rememberLocationEnabled()

    // Bumped after every action that can change association or permission state; both are read from the
    // system, not cached.
    var refreshKey by remember { mutableIntStateOf(0) }
    // Which app answers the walkie-talkie button, re-read on every refresh — the ON_RESUME bump below is what
    // brings a choice made in the system settings back here.
    val assistant = rememberAssistantTarget(refreshKey)
    val remoteConfigured = remember(refreshKey, setup) { setup.isAssociated() }
    val missingPermissions = remember(refreshKey, context) { missingPermissions(context) }
    // Whether the last association request was refused by the system; a cancelled dialog is not a failure.
    var setupFailed by remember { mutableStateOf(false) }

    // The association that applies, as established by CompanionSetup.reconcile(); null while none does, which
    // includes the moment a chosen remote is still waiting for its confirmation press.
    var effectiveAssociationId by remember { mutableStateOf<Int?>(null) }

    // The association this screen has already started the connection service for, so the attempt below happens
    // once per applicable remote and not again on every ON_RESUME. Saved across configuration changes like the
    // confirmation state: a remote that is out of range leaves no service behind, and every rotation would
    // otherwise be another connection attempt. A process death does repeat it, which is the intent.
    var serviceStartedFor by rememberSaveable { mutableStateOf(NO_ASSOCIATION) }

    // The association this screen is counting down for, NO_CONFIRMATION if none. Saved, so a rotation does not
    // leave the running confirmation without anybody counting its timeout.
    var confirmationRun by rememberSaveable { mutableStateOf(NO_CONFIRMATION) }
    var nowElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    // Read here and not in the lambda below: a resource lookup through LocalContext.current is not
    // configuration-aware (lint LocalContextGetResourceValueCall).
    val fallbackRemoteName = stringResource(R.string.remote_fallback_name)

    // The association whose confirmation was started, NO_CONFIRMATION if none. onAssociationCreated and the
    // activity result of the dialog both report a created association and the platform does not specify their
    // order, so starting the confirmation has to be idempotent — this is what makes it so. One value, cleared
    // when the confirmation ends: a growing set would also refuse a deliberate second attempt with the same
    // association, and it would be lost on an Activity recreation anyway.
    var confirmationStartedFor by rememberSaveable { mutableStateOf(NO_CONFIRMATION) }

    // Both values can change while this screen is in the background: the system settings are the only way back
    // from a "don't ask again" denial, and an association can be removed there as well. Re-reading them on
    // ON_RESUME is what keeps the screen from showing a stale mask after such a detour.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Bring associations and the confirmed one in line and observe the presence of that one — not of every
    // association, which would let the system start the service for a remote the user never confirmed.
    // Dispatchers.IO because reconcile() writes the preferences file.
    // bluetoothEnabled is a key, not just a condition: switching the adapter on has to repeat the attempt
    // below, which was deliberately skipped while it was off.
    LaunchedEffect(refreshKey, bluetoothEnabled, setup) {
        withContext(Dispatchers.IO) {
            val applicable = setup.reconcile()
            // Assigned before the observation call, which is the point where this coroutine could still be
            // cancelled: the screen would otherwise show "nicht eingerichtet" for a remote that applies.
            effectiveAssociationId = applicable
            if (applicable != null) setup.startObservingPresence(applicable)
            // A remote that applies but no service: the system reports a device as present once, so a remote
            // that is switched on and in range right now produces no new event — after an adoption, after a
            // crash or after "force-stop" nothing would ever start the connection (device test 17.10.2026).
            // One attempt per applicable remote, see [shouldStartConnectionService].
            val current = RemoteStatus.state.value
            if (
                shouldStartConnectionService(
                    applicableAssociationId = applicable,
                    serviceRunning = current.serviceRunning,
                    confirmationRunning = current.pendingConfirmation != null,
                    bluetoothEnabled = bluetoothEnabled,
                    alreadyTriedFor = serviceStartedFor.takeIf { it != NO_ASSOCIATION },
                ) &&
                applicable != null
            ) {
                Log.i(TAG, "UI: remote $applicable applies but no service runs, starting it")
                serviceStartedFor = applicable
                RemoteConnectionService.start(context, applicable)
            }
        }
    }

    // After an Activity recreation the running confirmation lives on in RemoteStatus; take it over, otherwise
    // nobody would count its timeout and the association would stay unconfirmed.
    LaunchedEffect(Unit) {
        val pendingId = RemoteStatus.state.value.pendingConfirmation?.associationId
        if (pendingId != null && confirmationRun == NO_CONFIRMATION) {
            Log.i(TAG, "UI: taking over the running confirmation of association $pendingId")
            confirmationStartedFor = pendingId
            confirmationRun = pendingId
        }
    }

    /**
     * The countdown shown on the card. It also ends the waiting when the deadline passes, but it is no longer
     * what guarantees it: the service enforces the same deadline on its own
     * ([RemoteConnectionService]), because this effect dies with the composition — pressing back would
     * otherwise leave a chosen remote waiting forever.
     */
    LaunchedEffect(confirmationRun) {
        val associationId = confirmationRun
        if (associationId == NO_CONFIRMATION) return@LaunchedEffect
        while (true) {
            // RemoteStatus directly instead of the collected state: collection stops below STARTED, so a press
            // that arrives while the screen is in the background would go unnoticed here and the timeout would
            // take back an association the user has just confirmed.
            val current = RemoteStatus.state.value
            val pending = current.pendingConfirmation
            if (pending == null || pending.associationId != associationId) {
                if (current.lastConfirmation == ConfirmationOutcome.CONFIRMED) {
                    Log.i(TAG, "UI: association $associationId confirmed by a button press")
                    withContext(Dispatchers.IO) {
                        // Written here as well as in the service: the service can be destroyed between the
                        // press and its write, and the next reconcile would then remove the association the
                        // user has just confirmed. Writing the same ID twice changes nothing, and it happens
                        // before the reconcile below (refreshKey) can see a stale pending marker.
                        setup.markConfirmed(associationId)
                        setup.startObservingPresence(associationId)
                    }
                }
                // Every other outcome was ended by whoever set it, together with the association.
                confirmationStartedFor = NO_CONFIRMATION
                confirmationRun = NO_CONFIRMATION
                refreshKey++
                return@LaunchedEffect
            }
            nowElapsedMs = SystemClock.elapsedRealtime()
            if (pending.isExpired(nowElapsedMs)) {
                Log.i(TAG, "UI: no confirmation press for association $associationId, undoing the association")
                RemoteStatus.onConfirmationFailed(ConfirmationOutcome.TIMED_OUT)
                PAIRING_SCOPE.launch { setup.cancelAssociation(associationId) }
                confirmationStartedFor = NO_CONFIRMATION
                confirmationRun = NO_CONFIRMATION
                refreshKey++
                return@LaunchedEffect
            }
            delay(COUNTDOWN_TICK_MS)
        }
    }

    /**
     * Starts the waiting for the confirmation press, from whichever of the two callbacks reports the created
     * association first. Nothing but the pending marker is persisted here: only a press makes this the user's
     * remote.
     */
    val beginConfirmation: (AssociationInfo) -> Unit = { info ->
        val associationId = info.id
        when {
            confirmationStartedFor == associationId ->
                Log.i(TAG, "UI: confirmation of association $associationId already started")
            // The press can only arrive over a BLE connection. Without Bluetooth there is nothing to wait for,
            // and an unconfirmed association must not survive, so it goes right away.
            !isBluetoothEnabled(context) -> {
                Log.w(TAG, "UI: Bluetooth is off, undoing association $associationId instead of confirming it")
                RemoteStatus.onConfirmationFailed(ConfirmationOutcome.BLUETOOTH_OFF)
                PAIRING_SCOPE.launch { setup.cancelAssociation(associationId) }
                refreshKey++
            }
            else -> {
                val label = setup.associationLabel(info) ?: fallbackRemoteName
                Log.i(TAG, "UI: waiting for the confirmation press on association $associationId ('$label')")
                // Everything that protects the new association happens here, synchronously, before anything
                // is launched: the reconcile that follows every refresh reads this state to know which
                // association must not be touched, and it is faster than any coroutine would be. The pending
                // marker is only the safety net for a process death and may therefore be written afterwards —
                // since the silent adoption became a one-time migration, an association without a marker is
                // removed on the next start just the same.
                val startedAt = SystemClock.elapsedRealtime()
                confirmationStartedFor = associationId
                nowElapsedMs = startedAt
                RemoteStatus.onConfirmationStarted(
                    PendingConfirmation(
                        associationId = associationId,
                        label = label,
                        deadlineElapsedMs = startedAt + CONFIRMATION_TIMEOUT_MS,
                    ),
                )
                confirmationRun = associationId
                setupFailed = false
                // The service is the only place that can receive the press; it connects to this association,
                // dispatches nothing while the confirmation runs, enforces the deadline and persists the press.
                RemoteConnectionService.confirm(context, associationId)
                refreshKey++
                // PAIRING_SCOPE, not the composition's: rotating or leaving the screen must not stop this
                // half way, and nothing here touches the composition except the state writes, which are safe
                // from any thread.
                PAIRING_SCOPE.launch {
                    if (setup.markPendingConfirmation(associationId)) return@launch
                    // Without the safety net there must be no waiting: a process death from here on would
                    // leave an association behind that nothing takes back.
                    Log.e(TAG, "UI: could not mark association $associationId as pending, undoing it")
                    RemoteStatus.onConfirmationFailed(ConfirmationOutcome.FAILED)
                    setup.cancelAssociation(associationId)
                    confirmationStartedFor = NO_CONFIRMATION
                    refreshKey++
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // Denial is not treated as an error: the screen keeps showing what is missing and the button stays.
        granted.filterValues { !it }.keys.forEach { Log.w(TAG, "UI: permission denied: $it") }
        // Granting BLUETOOTH_CONNECT afterwards is otherwise a dead end: a connection that failed with
        // FailureReason.MissingPermission keeps the service up with a permanent error and nobody retries,
        // because the remote stays present and the system sends no new BLE_APPEARED. One start from here is
        // enough and cannot duplicate anything: startForegroundService on the running service only delivers
        // another onStartCommand, and CratoniBleManager.connect is ignored while a connection exists, is being
        // set up or is scheduled. Allowed without the companion exemption as well, the app is in the
        // foreground.
        //
        // Only BLUETOOTH_CONNECT decides this, read from the system rather than from the result map: it is the
        // one the connection needs. POST_NOTIFICATIONS only makes the notification visible, and requiring it
        // too would leave whoever grants Bluetooth but denies notifications in exactly the dead end above.
        val bluetoothConnectGranted =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val pendingId = RemoteStatus.state.value.pendingConfirmation?.associationId
        val applicableId = effectiveAssociationId
        if (bluetoothConnectGranted) {
            when {
                // A running confirmation would otherwise wait for a press over a connection the service was
                // never allowed to open.
                pendingId != null -> RemoteConnectionService.confirm(context, pendingId)
                applicableId != null -> {
                    Log.i(TAG, "UI: BLUETOOTH_CONNECT granted, (re)starting the connection service")
                    RemoteConnectionService.start(context, applicableId)
                }
            }
        }
        refreshKey++
    }

    val associationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setupFailed = false
            val info = setup.associationFromResult(result.data)
                // The result should carry EXTRA_ASSOCIATION. If it does not, the association with the highest
                // ID is the one that was just created — the system hands them out increasing (assumed, logged
                // here so the device test can show it).
                ?: setup.associations().maxByOrNull { it.id }?.also {
                    Log.w(TAG, "UI: result without EXTRA_ASSOCIATION, falling back to association ${it.id}")
                }
            if (info == null) {
                Log.w(TAG, "UI: association created but no association found to confirm")
                setupFailed = true
            } else {
                beginConfirmation(info)
            }
        } else {
            Log.w(TAG, "UI: association cancelled (resultCode ${result.resultCode})")
        }
        refreshKey++
    }

    RemoteScreen(
        bluetoothEnabled = bluetoothEnabled,
        remoteConfigured = remoteConfigured,
        remoteState = state,
        missingPermissionNames = missingPermissions,
        notificationPermissionMissing = Manifest.permission.POST_NOTIFICATIONS in missingPermissions,
        callPermissionMissing = Manifest.permission.ANSWER_PHONE_CALLS in missingPermissions,
        // Only Samsung ships the "never sleep" battery optimisation the docs warn about, and it only matters
        // once a remote is actually set up.
        showBatteryOptimizationHint = IS_SAMSUNG_DEVICE && remoteConfigured,
        // Only relevant while setting up: CompanionDeviceManager.associate() requires the location switch on.
        showLocationHint = !remoteConfigured && !locationEnabled,
        assistantTarget = assistant?.target,
        onRequestPermissions = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
        onSetUp = {
            setupFailed = false
            setup.associate(
                executor = context.mainExecutor,
                onIntentSender = { sender ->
                    associationLauncher.launch(IntentSenderRequest.Builder(sender).build())
                },
                // Reported instead of or in addition to the activity result, in an unspecified order; both
                // paths end in the same idempotent beginConfirmation.
                onAssociationCreated = { info -> beginConfirmation(info) },
                // Called on context.mainExecutor, so writing the state here is safe. A refused request used to
                // end in the log only: the button looked as if nothing had happened. A rejected or cancelled
                // dialog is not a refusal and must not light up the error text.
                onFailure = { errorCode, error ->
                    if (CompanionSetup.isUserCancellation(errorCode)) {
                        Log.i(TAG, "UI: association rejected by the user (code $errorCode)")
                    } else {
                        Log.w(TAG, "UI: association request failed (code $errorCode): $error")
                        setupFailed = true
                    }
                },
            )
        },
        onForget = {
            // PAIRING_SCOPE, not the composition's: forget() writes the preferences file and removes the
            // associations, and half of that would be worse than none if the screen disappeared meanwhile.
            confirmationStartedFor = NO_CONFIRMATION
            PAIRING_SCOPE.launch {
                val removed = setup.forget()
                Log.i(TAG, "UI: $removed association(s) removed")
                // Only now: a refresh while forget() was still running would let the reconcile read the
                // association that is about to go — and start the connection service for it.
                serviceStartedFor = NO_ASSOCIATION
                refreshKey++
            }
        },
        onCancelConfirmation = {
            val associationId = RemoteStatus.state.value.pendingConfirmation?.associationId
            if (associationId == null) {
                Log.i(TAG, "UI: nothing to cancel, no confirmation is running")
            } else {
                Log.i(TAG, "UI: confirmation of association $associationId cancelled, undoing the association")
                // The state first, so the card disappears at once; the association follows in the background.
                RemoteStatus.onConfirmationFailed(ConfirmationOutcome.CANCELLED)
                PAIRING_SCOPE.launch { setup.cancelAssociation(associationId) }
                confirmationStartedFor = NO_CONFIRMATION
                refreshKey++
            }
        },
        // Opens the system page for the assistant choice; the screen only needs to know whether there was one.
        // The package comes from the same resolution as the shown name, so the app info page belongs to the app
        // the line names. Nothing is refreshed here: coming back from the settings is an ON_RESUME.
        onOpenAssistantSettings = { openAssistantSettings(context, assistant?.packageName) },
        modifier = modifier,
        setupFailed = setupFailed,
        confirmationRemainingSeconds = state.pendingConfirmation?.remainingSeconds(nowElapsedMs) ?: 0,
    )
}

/**
 * Whether Bluetooth is on, updated from [BluetoothAdapter.ACTION_STATE_CHANGED].
 *
 * `RECEIVER_NOT_EXPORTED` is correct here: the action is a protected system broadcast, no other app may send
 * it. Reading the adapter state needs no permission.
 */
@Composable
private fun rememberBluetoothEnabled(): Boolean {
    val context = LocalContext.current
    val adapter = remember(context) { context.getSystemService(BluetoothManager::class.java)?.adapter }
    var enabled by remember(adapter) { mutableStateOf(adapter?.isEnabled == true) }

    DisposableEffect(context, adapter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                enabled = adapter?.isEnabled == true
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return enabled
}

/**
 * Whether the system location switch is on, updated from [LocationManager.MODE_CHANGED_ACTION]. Only used for
 * [RemoteScreen]'s setup hint; reading it needs no permission.
 */
@Composable
private fun rememberLocationEnabled(): Boolean {
    val context = LocalContext.current
    val locationManager = remember(context) { context.getSystemService(LocationManager::class.java) }
    var enabled by remember(locationManager) { mutableStateOf(locationManager?.isLocationEnabled == true) }

    DisposableEffect(context, locationManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                enabled = locationManager?.isLocationEnabled == true
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(LocationManager.MODE_CHANGED_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return enabled
}

/**
 * Which app the system hands the walkie-talkie button over to, re-asked whenever [refreshKey] changes; `null`
 * until the first answer is there, which the screen shows as "Wird ermittelt …".
 *
 * On [Dispatchers.IO]: [resolveAssistantTarget] queries the package manager twice and loads an app label, all of
 * which reads from the system and none of which belongs on the main thread. No broadcast to listen to — the
 * choice can only change in the system settings or in the chooser, and both are a detour out of this screen, so
 * the existing `ON_RESUME` bump of [refreshKey] is exactly the right trigger.
 *
 * The previous answer stays visible while a [refreshKey] bump fetches a new one: a line that blinks back to
 * "Wird ermittelt …" on every return to the screen would say less, not more. That holds for a refresh only —
 * a configuration change such as a rotation recreates the activity and therefore starts at `null` again, which
 * is deliberate: the answer is back within milliseconds, so it is not worth saving.
 */
@Composable
private fun rememberAssistantTarget(refreshKey: Int): AssistantResolution? {
    val context = LocalContext.current
    var resolution by remember(context) { mutableStateOf<AssistantResolution?>(null) }

    LaunchedEffect(refreshKey, context) {
        val found = withContext(Dispatchers.IO) { resolveAssistantTarget(context.packageManager) }
        resolution = found
    }
    return resolution
}

/**
 * Current state of the Bluetooth adapter, read fresh instead of from the composition: the decision whether a
 * confirmation can be waited for at all must not be made from a value that was correct a recomposition ago.
 * Needs no permission.
 */
private fun isBluetoothEnabled(context: Context): Boolean =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/** The runtime permissions of [REQUIRED_PERMISSIONS] that are not granted yet, in declaration order. */
private fun missingPermissions(context: Context): List<String> = REQUIRED_PERMISSIONS.filter {
    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
}

/**
 * `BLUETOOTH_CONNECT` is needed for GATT and for the foreground service type `connectedDevice`;
 * `POST_NOTIFICATIONS` only makes its notification visible; `ANSWER_PHONE_CALLS` is what
 * [de.werklade.cratoniremote.action.TelecomCallExecutor] needs for the remote's call button. All three are
 * `dangerous` (`BLUETOOTH_CONNECT` and `POST_NOTIFICATIONS` verified on the device, `ANSWER_PHONE_CALLS`
 * from the android-37.0 stubs).
 *
 * Only the first one is essential: without `ANSWER_PHONE_CALLS` the call button stays without effect
 * (`hint_call_permission_missing` says so) and everything else keeps working, which is why a denial
 * triggers nothing beyond the log line in the launcher callback.
 */
private val REQUIRED_PERMISSIONS = listOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.ANSWER_PHONE_CALLS,
)

/** Gates the Samsung-specific "never sleep" battery optimisation hint (docs/architektur.md §7). */
private val IS_SAMSUNG_DEVICE: Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

/**
 * Scope for the pairing work that must not be cut short by a disappearing composition: writing the pending
 * marker, taking an association back, forgetting the remote.
 *
 * `rememberCoroutineScope()` is cancelled on rotation and when the Activity finishes, and every one of those
 * steps leaves the pairing in a worse state when it stops half way — an association without a marker, a
 * marker without an association. It lives as long as the process, like `RemoteStatus`, and does file and
 * system work only ([Dispatchers.IO]); nothing in it touches the composition.
 */
private val PAIRING_SCOPE = CoroutineScope(
    SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        // An unexpected RuntimeException from the companion framework would otherwise take the process with
        // it: a SupervisorJob isolates children from each other, not from an uncaught throwable.
        Log.e(TAG, "UI: pairing work failed", e)
    },
)

/** No confirmation is being counted down; no valid association ID (the system numbers them from 1). */
private const val NO_CONFIRMATION = -1

/** No association, for the saved marker of the connection service start; see [NO_CONFIRMATION]. */
private const val NO_ASSOCIATION = -1

/** One tick per second: the countdown is shown in whole seconds. */
private const val COUNTDOWN_TICK_MS = 1_000L

private const val TAG = CratoniBleManager.TAG
