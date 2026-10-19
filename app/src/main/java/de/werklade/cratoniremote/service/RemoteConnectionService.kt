package de.werklade.cratoniremote.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import de.werklade.cratoniremote.R
import de.werklade.cratoniremote.action.ActionDispatcher
import de.werklade.cratoniremote.action.AudioManagerMediaKeyExecutor
import de.werklade.cratoniremote.action.AudioManagerVolumeExecutor
import de.werklade.cratoniremote.action.DispatchResult
import de.werklade.cratoniremote.action.HelmetLightExecutor
import de.werklade.cratoniremote.action.MediaKeyAssistantExecutor
import de.werklade.cratoniremote.action.TelecomCallExecutor
import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.BleLog
import de.werklade.cratoniremote.ble.CratoniBleManager
import de.werklade.cratoniremote.ble.FailureReason
import de.werklade.cratoniremote.ble.PacketListener
import de.werklade.cratoniremote.companion.CompanionSetup
import de.werklade.cratoniremote.companion.ConfirmedRemoteStore
import de.werklade.cratoniremote.data.ConfirmationOutcome
import de.werklade.cratoniremote.data.RemoteStatus
import de.werklade.cratoniremote.helmet.HelmetConnectionState
import de.werklade.cratoniremote.helmet.HelmetSppClient
import de.werklade.cratoniremote.protocol.CratoniProtocolParser
import de.werklade.cratoniremote.protocol.ParseResult
import de.werklade.cratoniremote.ui.MainActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service of type `connectedDevice` that holds the BLE connection while the remote is present
 * (docs/architektur.md §3 "Betrieb").
 *
 * Lifecycle: started by [de.werklade.cratoniremote.companion.CratoniCompanionService] on device presence
 * events, so it runs while the system reports the remote as nearby and, beyond that, while it waits for
 * Bluetooth to be switched on again (see below) — no permanent notification, no permanent scanning. It is
 * stopped by that service as well, by [CompanionSetup.forget], and by itself when a connection attempt ends in
 * [BleConnectionState.Failed] with a reason a fresh start could fix ([FailureStopPolicy]).
 *
 * Wiring, one instance of each: FFF4 packet → [CratoniProtocolParser] → only [ParseResult.Command] →
 * [ActionDispatcher] (AudioManager implementations, [HelmetLightExecutor], [MediaKeyAssistantExecutor]) and
 * [RemoteStatus]. Invalid packets are logged and never dispatched. Battery measurements go to [RemoteStatus] for display and to
 * [LowBatteryNotifier], which warns once per threshold on its own audible channel.
 *
 * The helmet ([HelmetSppClient], classic Bluetooth/SPP, docs/architektur.md §13) is a second, independent
 * connection this service owns. It is opened as soon as the remote is connected and not only on the first light
 * button (decision of the project owner, 26.09.2026), so the first press is not delayed by the SDP lookup and
 * the blocking `connect()`. It is closed when Bluetooth is switched off — [HelmetSppClient.disconnect], which
 * keeps the client usable, because the service stays up and connects everything again on `STATE_ON` — and for
 * good in [onDestroy] ([HelmetSppClient.close]). Every fresh helmet connection makes [HelmetLightExecutor]
 * forget what the lights were doing, because the helmet was out of reach or off and its lights are off.
 *
 * A helmet that was off in that very moment is caught by a second runtime-registered receiver,
 * [helmetAclReceiver]: switching the helmet on makes it connect to the phone as a classic Bluetooth device, and
 * [BluetoothDevice.ACTION_ACL_CONNECTED] for it opens the SPP connection ([helmetShouldConnect]). Until then
 * the only way back was a light button press, which started a connection and lost its own frame
 * ([HelmetSppClient.send]) — that path is unchanged, it is just no longer the only one.
 *
 * Second job since the association dialog offers a list: [ACTION_CONFIRM_ASSOCIATION] connects to a remote the
 * user has chosen but not confirmed yet, so the confirmation press can be received here — the UI cannot, it has
 * no BLE connection. While that is running ([dispatchAllowed]) nothing is dispatched and the helmet is left
 * alone; the first valid packet confirms the remote, is persisted here ([persistConfirmation],
 * [ConfirmedRemoteStore]) and normal operation begins.
 *
 * Idempotent: repeated presence events only lead to another [CratoniBleManager.connect] for the same address,
 * which the manager ignores while connected, connecting or waiting for a scheduled reconnect. There is never
 * a second [CratoniBleManager] and never a second GATT client.
 *
 * Timers and Doze: the service itself schedules nothing. The reconnect path of the BLE layer does — the
 * delays of [de.werklade.cratoniremote.ble.ReconnectPolicy] (2/5/10 s) and the step timeouts (15/10/5 s) run
 * through [android.os.Handler.postDelayed] on [android.os.SystemClock.uptimeMillis], which stands still in
 * deep sleep, so those short reconnects can fire late or not at all while the device is suspended. Recovery
 * therefore does not rely on them but on the companion device presence events: a longer absence ends in
 * `BLE_DISAPPEARED` and a new `BLE_APPEARED`, which wakes the device and starts the service again. Whether an
 * [android.app.AlarmManager] is needed on top of that is open until the device test
 * (docs/architektur.md §11); none is used here, and no wake lock either.
 *
 * Bluetooth off/on: a runtime-registered receiver for [BluetoothAdapter.ACTION_STATE_CHANGED] (not allowed in
 * the manifest) disconnects on `STATE_OFF` but keeps the service up in the "waiting for Bluetooth" state
 * ([de.werklade.cratoniremote.data.RemoteState.waitingForBluetooth]), and connects again itself on `STATE_ON`.
 * Waiting instead of stopping is not an optimisation: the device test of 12.09.2026 showed that the system does
 * not report the remote as gone while Bluetooth is off, so it sends no new `BLE_APPEARED` when it comes back —
 * a stopped service would never be started again. The receiver only lives as long as the service, so the
 * service has to survive that window. A presence event that arrives while the adapter is off does not start a
 * connection attempt either; the service goes into the same waiting state instead of failing.
 *
 * That waiting is deliberately unbounded: there is no timeout after which the service gives up, so the
 * foreground notification (`notification_waiting_for_bluetooth`) stays in the status bar for as long as
 * Bluetooth is off — even overnight. It is the price for the behaviour above; a service that stopped after n
 * minutes would take its receiver with it and, without a new presence event, never come back on its own. The
 * only ways out of the waiting state are Bluetooth coming back (the service connects again), the remote being
 * forgotten ([CompanionSetup.forget]) and the user stopping the app.
 */
class RemoteConnectionService : Service() {

    private val setup: CompanionSetup by lazy { CompanionSetup(this) }

    /**
     * Where a confirmation press is persisted. Own instance next to the one inside [setup]; both work on the
     * same [android.content.SharedPreferences] file, which the framework hands out per name.
     */
    private val confirmedRemote: ConfirmedRemoteStore by lazy { ConfirmedRemoteStore(this) }
    private val notificationManager: NotificationManager? by lazy {
        getSystemService(NotificationManager::class.java)
    }

    /** Warns once per threshold when the battery of the remote runs low; own audible channel. */
    private val lowBatteryNotifier: LowBatteryNotifier by lazy { LowBatteryNotifier(this) }

    /**
     * SPP connection to the helmet. Created on the first access, which is [observeHelmet] in [onCreate], so it
     * exists for the whole life of the service and [onDestroy] always has something to close. It owns one thread
     * from then on and opens no socket until the remote is connected.
     */
    private val helmet: HelmetSppClient by lazy { HelmetSppClient(this) }

    /** Keeps the state of the helmet's lights and publishes it for display. */
    private val lights: HelmetLightExecutor by lazy {
        HelmetLightExecutor(helmet) { lights -> RemoteStatus.onHelmetLights(lights) }
    }

    /** `null` if the system has none; then nothing is dispatched (logged in [dispatcher]). */
    private val audioManager: AudioManager? by lazy { getSystemService(AudioManager::class.java) }

    /**
     * Sends the voice-key long press of the walkie-talkie button. One instance for the whole life of the service,
     * shared by [dispatcher] and [onDestroy]; `null` exactly when [audioManager] is.
     *
     * [onDestroy] closes it unconditionally, i.e. it creates the instance there if no packet ever needed it. That is
     * deliberate: a packet that is still being handled on the BLE thread when the service is destroyed builds
     * [dispatcher] with this same — then closed — instance, so its `startAssistant()` returns `false` instead of
     * starting a sequence on a dead service. Skipping the close when the instance does not exist yet would reopen
     * exactly that window. Creating it is harmless: it only allocates a [android.os.Handler] on the main looper and
     * a lock, owns no thread, registers nothing with the system, and closing it before any press only removes its
     * own (non-existent) callbacks. The lazy delegate is synchronized, so both threads see the same instance.
     */
    private val assistant: MediaKeyAssistantExecutor? by lazy {
        audioManager?.let { MediaKeyAssistantExecutor(it) }
    }

    /** `null` if there is no [AudioManager]; then nothing is dispatched (logged). */
    private val dispatcher: ActionDispatcher? by lazy {
        val audioManager = audioManager
        val assistant = assistant
        if (audioManager == null || assistant == null) {
            Log.e(TAG, "Service: AudioManager unavailable, packets will not be dispatched")
            null
        } else {
            ActionDispatcher(
                AudioManagerMediaKeyExecutor(audioManager),
                AudioManagerVolumeExecutor(audioManager),
                TelecomCallExecutor(this, audioManager),
                lights,
                assistant,
            )
        }
    }

    // Dispatchers.Default because only kotlinx-coroutines-core is a dependency; NotificationManager and
    // the state flows are thread-safe, no main thread is needed. With a handler since this scope also runs
    // companion framework work ([resolveMigrationThenConnect]): `getMyAssociations` and `disassociate` can
    // throw, and a SupervisorJob isolates children from each other, not from an uncaught throwable.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Service: background work failed", e)
        },
    )

    /**
     * Scope for the two pieces of work that must finish even if the service is destroyed in the same moment:
     * persisting a confirmation press and taking a chosen association back. Its job is [NonCancellable], so
     * [onDestroy]'s `scope.cancel()` does not reach them — a press that was answered with "confirmed" on the
     * screen must not be lost, and an association nobody confirmed must not survive because the service died
     * a millisecond too early. Both are short file writes plus a system call, nothing that outlives the
     * process in a noticeable way.
     */
    private val criticalScope = CoroutineScope(
        Dispatchers.IO + NonCancellable + CoroutineExceptionHandler { _, e ->
            // Without a handler an unexpected RuntimeException from the companion framework would take the
            // process with it, because this scope has no parent that could absorb it.
            Log.e(TAG, "Service: pairing work failed", e)
        },
    )

    /**
     * Volatile because the BLE thread reads it in [onPacket] while the main thread assigns it in [manager]:
     * a stale null there would clear the battery level right after a confirmation instead of publishing it.
     */
    @Volatile
    private var bleManager: CratoniBleManager? = null
    private var startedForeground = false
    private var receiverRegistered = false

    /** Own flag for [helmetAclReceiver], like [receiverRegistered] for [bluetoothReceiver]. */
    private var helmetReceiverRegistered = false

    /** Own flag for [localeReceiver], like [receiverRegistered] for [bluetoothReceiver]. */
    private var localeReceiverRegistered = false

    /**
     * Association of the remote this service is running for, from the last presence event;
     * [NO_ASSOCIATION_ID] means "the only association" (see [CompanionSetup.remoteDevice]). Kept so the service
     * can connect again on its own after Bluetooth comes back, without a new presence event.
     */
    private var associationId: Int = NO_ASSOCIATION_ID

    /** Runs while a confirmation is being waited for; see [startConfirmationTimeout]. */
    private var confirmationTimeoutJob: Job? = null

    /**
     * `true` from the first [resolveMigrationThenConnect] on and never reset again: settling the pairing a
     * second time in the same service instance cannot help, and it is the only thing that keeps a failing
     * preferences file (a full disk makes `commit()` return `false`) from turning the retry into an endless
     * row of reconciles.
     */
    private var migrationResolveAttempted = false

    /**
     * `true` while that one attempt is in flight. A second start command in that window only has to wait for
     * it, not to stop the service the first one is about to connect. Both flags are read and written on the
     * main thread only ([onStartCommand] and the continuation of [resolveMigrationThenConnect]).
     */
    private var migrationResolveRunning = false

    /**
     * `true` from [onDestroy] on. Read in the one place that comes back to the main thread after waiting for
     * the reconcile: nothing may be connected on a service the system has already destroyed.
     */
    private var destroyed = false

    /**
     * `startId` of the last [onStartCommand], [NO_START_ID] before the first one. See [stopSelfSafely].
     */
    private var lastStartId: Int = NO_START_ID

    /**
     * The line of the notification as it was last handed to the system, `null` before the first one.
     *
     * Everything that posts goes through [postNotification] and compares against this, so the two paths —
     * the state flow and the language refresh — cannot undo each other, and a configuration change that is
     * not a language change costs nothing. It is the same rule as the `distinctUntilChanged()` in
     * [observeStatusForNotification], only in one place for both.
     */
    private var lastPostedText: String? = null

    /**
     * Guards [lastPostedText] *together with* the `notify` that follows it: the flow collector runs on
     * [Dispatchers.Default] and the language refresh on the main thread, and without that bracket the older
     * of two lines could reach the system last. `notify` is a short binder call and the two paths practically
     * never meet, so the lock costs nothing measurable.
     */
    private val notificationLock = Any()

    /**
     * The language the notification channels and the notification line were last built in, `null` until
     * [refreshLocalisedText] ran the first time. Only read and written on the main thread (service creation,
     * [onConfigurationChanged], [localeReceiver]).
     *
     * It is what tells a language change apart from every other configuration change, and the log prints it
     * as "old -> new", so a device test can see at a glance whether the trigger arrived *and* whether the
     * resources behind it had already been switched over.
     */
    private var renderedLocale: Locale? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, UNKNOWN_BLUETOOTH_STATE)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Service: Bluetooth is being switched off, disconnecting and waiting for it")
                    bleManager?.disconnect()
                    helmet.disconnect()
                    // A confirmation cannot survive this: without Bluetooth no press can arrive, and waiting
                    // for the adapter would leave "Tastendruck wird erwartet" on the screen until the timeout
                    // reported the wrong reason. It ends right here, with the reason the user can act on.
                    val pendingId = RemoteStatus.state.value.pendingConfirmation?.associationId
                    if (pendingId != null) {
                        failConfirmation(pendingId, ConfirmationOutcome.BLUETOOTH_OFF)
                    } else {
                        waitForBluetooth()
                    }
                }
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Service: Bluetooth is on, connecting again (association $associationId)")
                    connectToRemote(associationId)
                }
                else -> Log.d(TAG, "Service: Bluetooth state $state ignored")
            }
        }
    }

    /**
     * Notices the helmet being switched on. Runtime-registered like [bluetoothReceiver] (both actions are
     * protected system broadcasts and may not be declared in the manifest since Android 8).
     *
     * The helmet connects to the phone as a classic Bluetooth device (headset/hands-free) when it is switched
     * on, which the system reports as [BluetoothDevice.ACTION_ACL_CONNECTED]. Before this receiver existed the
     * SPP connection was only attempted in the moment the remote connected, so a helmet that was off or out of
     * range at that point stayed unreachable until a light button was pressed — and that first press was lost
     * ([HelmetSppClient.send]), the status card said "Helm nicht erreichbar" until then (device test
     * 17.10.2026).
     *
     * [helmetShouldConnect] decides. [BluetoothDevice.ACTION_ACL_DISCONNECTED] never connects: the ACL link is
     * the link the RFCOMM channel runs on, so when it goes the socket is dead, and the client would only notice
     * that on the next write (an [java.io.IOException] in its `sendOnWorker`, i.e. one lost frame). Closing it
     * here keeps the published state and the status card honest and leaves the client ready for the next
     * `connect()` — the same thing the `STATE_OFF` branch above does, and for the same reason.
     *
     * Nothing else in the helmet path changes: the connection at "remote connected", the silence during a
     * confirmation, [HelmetLightExecutor.reset] on a fresh connection and the reconnect through
     * [HelmetSppClient.send] all stay as they are.
     */
    private val helmetAclReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
                action != BluetoothDevice.ACTION_ACL_DISCONNECTED
            ) {
                return
            }
            // Both actions carry EXTRA_TRANSPORT. The SPP socket lives on BR/EDR only, so an LE event of the
            // same address says nothing about it: acting on an LE disconnect would close a healthy socket, and
            // an LE connect would start a pointless attempt. Our own GATT connections to the remote raise LE
            // events too, which is why this comes before anything that costs a binder call.
            val transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR)
            if (transport == BluetoothDevice.TRANSPORT_LE) {
                Log.d(TAG, "Helmet: $action over LE ignored, the control connection is classic Bluetooth")
                return
            }
            val device = aclDevice(intent) ?: return
            val helmetAddress = helmet.knownAddress()
            if (!isHelmetDevice(device, helmetAddress, HelmetSppClient.HELMET_NAME_PREFIX)) {
                Log.d(TAG, "Helmet: $action of ${BleLog.mac(device.address)} is not the helmet, ignored")
                return
            }
            val helmetState = helmet.connectionState.value
            if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                if (helmetState == HelmetConnectionState.Connected ||
                    helmetState == HelmetConnectionState.Connecting
                ) {
                    Log.i(TAG, "Helmet: switched off or out of range, closing the dead SPP socket")
                    helmet.disconnect()
                } else {
                    Log.d(TAG, "Helmet: disconnected as a classic device, no socket to close ($helmetState)")
                }
                return
            }
            val remoteConnected = bleManager?.connectionState?.value == BleConnectionState.Connected
            val allowed = dispatchAllowed(RemoteStatus.state.value)
            val connect = helmetShouldConnect(
                device,
                helmetAddress,
                HelmetSppClient.HELMET_NAME_PREFIX,
                helmetState,
                allowed,
                remoteConnected,
            )
            if (connect) {
                Log.i(TAG, "Helmet: switched on (${BleLog.mac(device.address)}), opening the SPP connection")
                helmet.connect()
            } else {
                Log.i(
                    TAG,
                    "Helmet: connected as a classic device, no SPP connection " +
                        "(helmet=$helmetState, remote connected=$remoteConnected, dispatch allowed=$allowed)",
                )
            }
        }
    }

    /**
     * Notices a language change, as the second trigger next to [onConfigurationChanged]. Runtime-registered
     * like the two receivers above ([Intent.ACTION_LOCALE_CHANGED] is a protected system broadcast and may
     * not be declared in the manifest since Android 8).
     *
     * ## Why two triggers
     *
     * Device test 19.10.2026 (S25 Ultra, Android 16/One UI 8.5): switching the *system* language turned the
     * screen English at once, while the permanently visible notification stayed German until the user swiped
     * it away and a state change posted it again — the [onConfigurationChanged] path did not do its job.
     *
     * Read in the platform sources (API 37, `ActivityThread`/`ConfigurationController`) it should have:
     * `collectComponentCallbacks(includeUiContexts = false)` deliberately keeps every plain [Service] (only a
     * `WindowProviderService` is left out), `android:configChanges` is an activity attribute and means nothing
     * for a service, and `ResourcesManager.applyConfigurationToResources` runs *before* the callbacks, so the
     * resources are already switched over when one arrives. But `handleConfigurationChangedInner` has two
     * gates in front of the callbacks — a configuration whose sequence number is not newer than the one the
     * process already has, and a configuration that differs from it in no public field, are both dropped after
     * the resources were updated — and there are several paths that consume the pending configuration (an
     * activity being relaunched for the very same language change is one of them). Which of those swallowed
     * the callback cannot be read off the sources, only off a device.
     *
     * So this receiver is not a workaround for a wrong diagnosis but the trigger that does not depend on that
     * dispatch at all: [Intent.ACTION_LOCALE_CHANGED] is the documented signal for "the language changed", it
     * is sent to every running process, and it cannot be lost in a sequence-number comparison.
     * [onConfigurationChanged] stays, because the broadcast does *not* cover the other direction: a per-app
     * language picked in the system settings (the app offers one, `android:localeConfig`) changes the
     * configuration of this app alone and sends no `ACTION_LOCALE_CHANGED`. Both end in
     * [refreshLocalisedText], which is idempotent, so whichever comes first — or both — is fine.
     *
     * Nothing is read from the intent; the new language comes from this service's own resources. A forged
     * broadcast could therefore do no more than make the service fetch its own texts again, which is why
     * [Context.RECEIVER_EXPORTED] is acceptable here. It is also the flag that is *proven* to work on the
     * test device: with [Context.RECEIVER_NOT_EXPORTED] the protected ACL broadcasts above never arrived
     * (measured 17.10.2026), and this trigger exists precisely because the documented path already failed
     * once. If the device test shows the broadcast arriving, the flag can be tightened.
     */
    private val localeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_LOCALE_CHANGED) return
            refreshLocalisedText("ACTION_LOCALE_CHANGED")
        }
    }

    /**
     * The device of an ACL broadcast, or `null` if there is nothing usable to decide on — then the event is
     * dropped silently, like everywhere else in this app when `BLUETOOTH_CONNECT` is missing.
     *
     * Both [BluetoothDevice.getAddress] and [BluetoothDevice.getName] are behind `BLUETOOTH_CONNECT` for an app
     * targeting Android 12 or later; the permission is checked here (lint only sees a check in the same method)
     * and a [SecurityException] is caught on top, because a revoked permission must not take the service down
     * from inside a broadcast.
     */
    private fun aclDevice(intent: Intent): AclDevice? {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        if (device == null) {
            Log.w(TAG, "Helmet: ${intent.action} without a device, ignored")
            return null
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Helmet: ${intent.action} ignored, BLUETOOTH_CONNECT is not granted")
            return null
        }
        return try {
            AclDevice(device.address, device.name)
        } catch (e: SecurityException) {
            Log.w(TAG, "Helmet: ${intent.action} ignored, reading the device was refused", e)
            null
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service: created")
        // Creates both notification channels and remembers the language they were created in; nothing is
        // posted yet, the service is not in the foreground at this point.
        refreshLocalisedText("service created")
        registerReceiver(
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        registerReceiver(
            helmetAclReceiver,
            IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED).apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            // Measured on the test device (17.10.2026): with RECEIVER_NOT_EXPORTED the ACL broadcasts never
            // arrive, although other apps receive them and BLUETOOTH_CONNECT is granted. Both actions are
            // protected broadcasts, so only the system can send them and exporting the receiver opens nothing:
            // a forged event could at most ask for a connection to the helmet this app already knows.
            Context.RECEIVER_EXPORTED,
        )
        helmetReceiverRegistered = true
        // See [localeReceiver] for why this trigger exists next to onConfigurationChanged and why it is
        // registered exported.
        registerReceiver(
            localeReceiver,
            IntentFilter(Intent.ACTION_LOCALE_CHANGED),
            Context.RECEIVER_EXPORTED,
        )
        localeReceiverRegistered = true
        observeHelmet()
        // Opens the pairing preferences file off the main thread, so the first "is this the confirmed remote?"
        // in [connectToRemote] is less likely to wait for the disk. Nobody waits for this, so it is no
        // guarantee — just a head start (see ConfirmedRemoteStore.warmUp).
        criticalScope.launch { setup.warmUp() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!startedForeground) {
            if (!startForegroundSafely()) {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            startedForeground = true
            RemoteStatus.onServiceStarted()
            observeStatusForNotification()
        }
        when (intent?.action) {
            ACTION_DEVICE_APPEARED -> connectToRemote(intent.getIntExtra(EXTRA_ASSOCIATION_ID, NO_ASSOCIATION_ID))
            ACTION_CONFIRM_ASSOCIATION -> {
                val associationId = intent.getIntExtra(EXTRA_ASSOCIATION_ID, NO_ASSOCIATION_ID)
                // Only if the connection was allowed and started: after a refusal this service is already
                // stopping, and a timer that dies with it would be worse than none.
                if (connectToRemote(associationId)) startConfirmationTimeout(associationId)
            }
            // START_NOT_STICKY: without a presence event there is nothing to connect to.
            null -> {
                Log.w(TAG, "Service: started without intent, stopping")
                stopSelfSafely()
            }
            else -> Log.w(TAG, "Service: unknown action ${intent.action} ignored")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service: destroyed")
        destroyed = true
        // No service means nothing can receive a confirmation press any more, so no confirmation may outlive
        // this. Every way this service can end while one runs leads here — the remote did not answer, the
        // foreground start was refused, a presence event for another association arrived — and without this
        // the chosen association would stay "waiting" for good: all buttons dead, the pairing left behind,
        // and nobody counting the deadline once the Activity is gone as well.
        RemoteStatus.state.value.pendingConfirmation?.let { pending ->
            failConfirmation(pending.associationId, ConfirmationOutcome.FAILED)
        }
        if (receiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service: receiver was not registered", e)
            }
            receiverRegistered = false
        }
        if (helmetReceiverRegistered) {
            try {
                unregisterReceiver(helmetAclReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service: helmet receiver was not registered", e)
            }
            helmetReceiverRegistered = false
        }
        if (localeReceiverRegistered) {
            try {
                unregisterReceiver(localeReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service: locale receiver was not registered", e)
            }
            localeReceiverRegistered = false
        }
        confirmationTimeoutJob = null
        scope.cancel()
        bleManager?.close()
        bleManager = null
        helmet.close()
        // Main thread, as close() requires; see [assistant] for why this may create the instance.
        assistant?.close()
        RemoteStatus.onServiceStopped()
        super.onDestroy()
    }

    /** Not a bound service. */
    override fun onBind(intent: Intent): IBinder? = null

    /**
     * One of the two triggers for a language change (see [localeReceiver] for the other one and for why there
     * are two). Everything else a configuration change can be — a rotation, the dark mode, a new font size —
     * arrives here as well and is dropped: the language in [newConfig] is then the one the texts were already
     * built in, and nothing in this service depends on anything else in that configuration.
     *
     * Dropping it is what keeps a rotation from rebuilding and re-posting the notification and from writing a
     * log line about it. The second guard is in [postNotification], which drops an unchanged line in any case.
     *
     * The language of [newConfig] is logged in the trigger, while [refreshLocalisedText] logs the language it
     * actually *rendered* in (from its own resources). If those two ever disagree, the log says so — that is
     * the difference between "the trigger never came" and "it came before the resources were switched over".
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val locale = newConfig.locales[0]
        if (locale == renderedLocale) {
            Log.d(TAG, "Service: configuration changed, language is still $locale, nothing to fetch again")
            return
        }
        refreshLocalisedText("configuration change to $locale")
    }

    /**
     * Fetches everything the user reads in words again: the names and descriptions of the two notification
     * channels and the single line of the foreground notification.
     *
     * Called when the service is created and from both language triggers. Doing it twice is free, which is
     * what allows the two triggers to overlap: creating a channel that exists only hands over name,
     * description and group (see [createNotificationChannel]), and [postNotification] drops a line that did
     * not change.
     *
     * The channels are refreshed even while the service is not in the foreground yet — they are visible in
     * the system settings whether a notification stands or not.
     *
     * @param trigger what caused this, for the log; it carries the language of the trigger where there is one
     */
    private fun refreshLocalisedText(trigger: String) {
        val locale = resources.configuration.locales[0]
        val previous = renderedLocale
        renderedLocale = locale
        createNotificationChannel()
        lowBatteryNotifier.createChannel()
        val text = if (startedForeground) remoteNotificationText(RemoteStatus.state.value) else null
        Log.i(
            TAG,
            "Service: texts fetched again ($trigger): language $previous -> $locale, both channels updated, " +
                (text?.let { "notification line '$it'" } ?: "no notification yet (not in the foreground)"),
        )
        if (text != null) postNotification(text, trigger)
    }

    /**
     * Hands [text] to the system, unless that very line is already standing there.
     *
     * The one place both paths post through — the state flow and [refreshLocalisedText] — so a language
     * change and a state change cannot overtake each other and nothing is posted twice (see
     * [lastPostedText], [notificationLock]).
     *
     * @param reason what led to this line, for the log
     */
    private fun postNotification(text: String, reason: String) {
        synchronized(notificationLock) {
            if (text == lastPostedText) {
                Log.d(TAG, "Service: notification line unchanged, not posted again ($reason)")
                return
            }
            lastPostedText = text
            Log.d(TAG, "Service: notification text '$text' ($reason)")
            notificationManager?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    /**
     * Connects to the remote of [associationId], or waits for Bluetooth if the adapter is off.
     *
     * Called from the presence event, from [ACTION_CONFIRM_ASSOCIATION] and from the receiver on `STATE_ON`,
     * so [associationId] is remembered for the latter — but only once the gate let it through, otherwise a
     * refused presence event would overwrite the association this service is actually running for.
     * [CratoniBleManager.connect] is idempotent for the same address: a running attempt, a live connection and
     * a scheduled reconnect are left alone, so a second call cannot disturb them. There is never a second
     * [CratoniBleManager] and never a second GATT client — the remote accepts only one connection, which is
     * why the confirmation uses this same path instead of its own.
     *
     * [connectAllowed] decides, and it needs no extra flag for the confirmation: while one is running, the
     * association being confirmed is the only one that may be connected to anyway. Everything else is stopped
     * instead of connected — that covers a presence event for a remote that was forgotten in the meantime as
     * well as one for a left-over association nobody confirmed.
     *
     * @return `false` if nothing was started and this service is stopping, waiting for Bluetooth or waiting
     *   for the migration decision instead
     */
    private fun connectToRemote(associationId: Int): Boolean {
        val verdict = connectDecision(
            state = RemoteStatus.state.value,
            confirmedId = setup.confirmedAssociationId(),
            requestedId = associationId,
            legacyMigrationDone = setup.isLegacyMigrationDone(),
        )
        when (verdict) {
            ConnectVerdict.CONNECT -> Unit
            ConnectVerdict.RESOLVE_MIGRATION -> {
                resolveMigrationThenConnect(associationId)
                return false
            }
            ConnectVerdict.REFUSE -> {
                val pendingId = RemoteStatus.state.value.pendingConfirmation?.associationId
                if (pendingId != null) {
                    // Stopping here would destroy this service, and with it the confirmation it is holding
                    // the connection for ([onDestroy] takes the fresh association back). A presence event for
                    // some other association is no reason to cancel the setup the user is in the middle of.
                    Log.i(TAG, "Service: ignoring association $associationId, confirming $pendingId")
                } else {
                    Log.w(TAG, "Service: association $associationId is not the confirmed remote, stopping")
                    stopSelfSafely()
                }
                return false
            }
        }
        this.associationId = associationId
        if (isBluetoothOff()) {
            waitForBluetooth()
            return false
        }
        val device = setup.remoteDevice(associationId.takeIf { it != NO_ASSOCIATION_ID })
        if (device == null) {
            Log.w(TAG, "Service: no associated remote for association $associationId, stopping")
            stopSelfSafely()
            return false
        }
        RemoteStatus.onWaitingForBluetooth(false)
        Log.i(TAG, "Service: connecting to ${BleLog.mac(device.address)} (association $associationId)")
        manager().connect(device)
        return true
    }

    /**
     * Settles a still open one-time migration and then tries [associationId] again, exactly once.
     *
     * Needed because the system can report the remote as present before the user has opened the app after an
     * update (device test 17.10.2026): the pairing of the earlier version was not confirmed yet, the Activity
     * was the only place that adopted it, so the service refused and the remote stayed dead until it was
     * switched off and on again.
     *
     * The decision is not made here: [CompanionSetup.reconcile] runs the same rules the UI uses
     * (`companion.PairingReconciler`), so there is one rule path and not two. It does binder calls and writes
     * the preferences file, which must not happen on the main thread — hence the hop to [Dispatchers.IO] and
     * back through [getMainExecutor]. The foreground start has already happened at this point
     * ([onStartCommand]), so nothing is delayed that the system is waiting for.
     *
     * Runs at most once per service instance ([migrationResolveAttempted]). The reconcile closes the
     * migration window in the normal case, so the retry can only end in [ConnectVerdict.CONNECT] or
     * [ConnectVerdict.REFUSE] — but that depends on a write that can fail (a full disk), which is why the flag
     * is never reset instead of being trusted. Runs in [scope], so it dies with the service; the continuation
     * checks [destroyed] as well, because a posted runnable is not cancelled with it.
     *
     * The retry connects the association the reconcile settled on, not the one that was asked for: if the
     * pairing that applies turns out to be a different one, that is the remote to connect to, and
     * [connectDecision] guards it just the same.
     */
    private fun resolveMigrationThenConnect(associationId: Int) {
        if (migrationResolveRunning) {
            Log.i(TAG, "Service: the pairing is being settled, association $associationId waits for it")
            return
        }
        if (migrationResolveAttempted) {
            // The one attempt is over and still nothing is confirmed, so the write it depended on failed.
            // Another reconcile would do the same work with the same result.
            Log.w(TAG, "Service: the pairing was already settled in this service, stopping")
            stopSelfSafely()
            return
        }
        migrationResolveAttempted = true
        migrationResolveRunning = true
        Log.i(TAG, "Service: nothing confirmed yet, settling the pairing before connecting ($associationId)")
        scope.launch(Dispatchers.IO) {
            val applicable = setup.reconcile()
            // The observation belongs to the adoption: after an `install -r` the system may have dropped it,
            // and until now only the UI re-established it — so without this the remote would be connected once
            // and never reported as present again until the app is opened.
            if (applicable != null) setup.startObservingPresence(applicable)
            mainExecutor.execute {
                migrationResolveRunning = false
                if (destroyed) {
                    Log.i(TAG, "Service: destroyed while settling the pairing, nothing to connect")
                    return@execute
                }
                if (applicable == null) {
                    Log.w(TAG, "Service: no remote applies after settling the pairing, stopping")
                    stopSelfSafely()
                    return@execute
                }
                Log.i(TAG, "Service: pairing settled, association $applicable applies")
                connectToRemote(applicable)
            }
        }
    }

    /**
     * Enforces the confirmation deadline for [associationId] from here, where the connection is.
     *
     * The UI counts the same deadline for its countdown, but it cannot be relied on: pressing back destroys
     * the Activity while this service keeps its connection to the chosen remote. Without this timer the
     * waiting would never end — every button would stay dead, and a press by the rightful owner of a remote
     * the user picked by mistake would make it "the remote" minutes later.
     *
     * The deadline comes from the state the UI wrote before starting this service
     * ([de.werklade.cratoniremote.data.PendingConfirmation.deadlineElapsedMs]) and is checked against
     * [SystemClock.elapsedRealtime], which keeps running in deep sleep. `delay` does not, so the wait is
     * broken into steps of at most [CONFIRMATION_CHECK_INTERVAL_MS] and re-measured against that clock every
     * time. The loop also ends as soon as the waiting ended some other way.
     */
    private fun startConfirmationTimeout(associationId: Int) {
        val pending = RemoteStatus.state.value.pendingConfirmation
        if (pending == null || pending.associationId != associationId) {
            Log.w(TAG, "Service: nothing to confirm for association $associationId, stopping")
            stopSelfSafely()
            return
        }
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = scope.launch {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val current = RemoteStatus.state.value.pendingConfirmation
                if (current == null || current.associationId != associationId) {
                    Log.i(TAG, "Service: confirmation of association $associationId already settled")
                    return@launch
                }
                if (current.isExpired(now)) {
                    failConfirmation(associationId, ConfirmationOutcome.TIMED_OUT)
                    return@launch
                }
                delay(minOf(current.deadlineElapsedMs - now, CONFIRMATION_CHECK_INTERVAL_MS))
            }
        }
    }

    /**
     * Ends a confirmation that did not end in a press: the waiting is over in the state, the association is
     * taken back and this service has nothing left to do.
     *
     * Does nothing if [associationId] is not the association currently being confirmed — the press and this
     * can race, and the press wins. The association is removed in [criticalScope], so stopping the service
     * right after cannot cut that off.
     */
    private fun failConfirmation(associationId: Int, outcome: ConfirmationOutcome) {
        if (RemoteStatus.state.value.pendingConfirmation?.associationId != associationId) return
        Log.i(TAG, "Service: confirmation of association $associationId ends as $outcome, taking it back")
        RemoteStatus.onConfirmationFailed(outcome)
        criticalScope.launch { setup.cancelAssociation(associationId) }
        // Harmless while the service is already being destroyed: stopSelf on a dying service does nothing.
        stopSelfSafely()
    }

    /**
     * Stays up without a connection attempt and shows `notification_waiting_for_bluetooth` instead of an
     * error; the receiver connects again on `STATE_ON`. Only if the remote is not set up (any more) does the
     * service stop — then nothing will ever connect again and the notification would be pointless.
     *
     * The adapter is checked again right before the state is set: this runs from the receiver on the main
     * thread as well as from the connection-state collector on [Dispatchers.Default], so a `STATE_ON` can
     * already have been handled when a late [FailureReason.BluetoothDisabled] arrives here. Setting the waiting
     * state then would leave it stuck on a connection that is being established, so this does nothing instead —
     * the `STATE_ON` path has already called [connectToRemote].
     */
    private fun waitForBluetooth() {
        if (!setup.isAssociated()) {
            Log.w(TAG, "Service: Bluetooth is off and no remote is associated, stopping")
            stopSelfSafely()
            return
        }
        if (!isBluetoothOff()) {
            Log.i(TAG, "Service: Bluetooth is on again, not waiting for it")
            return
        }
        Log.i(TAG, "Service: Bluetooth is off, staying up and waiting for it to be switched on")
        RemoteStatus.onWaitingForBluetooth(true)
    }

    /**
     * `true` only if there is an adapter and it is switched off. Without an adapter this returns `false` on
     * purpose: waiting for a `STATE_ON` that cannot come would hide the real failure, so the BLE layer runs and
     * reports [FailureReason.BluetoothAdapterMissing]. Reading the state needs no permission.
     */
    private fun isBluetoothOff(): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return !adapter.isEnabled
    }

    /** Creates the single [CratoniBleManager] on first use and starts publishing its state. */
    private fun manager(): CratoniBleManager = bleManager ?: CratoniBleManager(
        this,
        PacketListener { packet -> onPacket(packet) },
    ).also { manager ->
        bleManager = manager
        scope.launch {
            manager.connectionState.collect { state ->
                RemoteStatus.onConnectionState(state)
                // The helmet is connected as soon as the remote is, so the first light button press only has to
                // write a frame. connect() is idempotent and does not block.
                //
                // Not while a confirmation is running: that connection exists to receive one press from a
                // remote that may not even belong to this user, and opening the SPP connection to the helmet
                // for it would be work for a pairing that is about to be taken back. [onPacket] connects the
                // helmet the moment the press confirms the remote.
                if (state == BleConnectionState.Connected && dispatchAllowed(RemoteStatus.state.value)) {
                    helmet.connect()
                }
                if (state is BleConnectionState.Failed) onConnectionFailed(state.reason)
            }
        }
        // Both collectors stay silent while a confirmation is running: the measurement belongs to a remote
        // that may not be the user's at all. Showing its charge would be wrong, and the warning would be worse
        // — it is audible and it would store its threshold for a remote that is about to be taken back.
        //
        // The battery is read once per connection, so that one value would be lost for good: the flow does not
        // emit it again. [onPacket] therefore publishes the manager's current value the moment the press
        // confirms the remote.
        scope.launch {
            manager.battery.collect { if (dispatchAllowed(RemoteStatus.state.value)) RemoteStatus.onBattery(it) }
        }
        // Own collector on Dispatchers.IO: the warning reads and writes SharedPreferences, which must not happen
        // on the BLE thread (it delivers the button packets) nor on the main thread.
        scope.launch(Dispatchers.IO) {
            manager.battery.collect {
                if (dispatchAllowed(RemoteStatus.state.value)) lowBatteryNotifier.onBattery(it)
            }
        }
    }

    /**
     * Reaction to [BleConnectionState.Failed], i.e. after the manager gave up ([FailureStopPolicy]): stop, so
     * that the next presence event starts a fresh attempt and the notification disappears meanwhile; wait for
     * the adapter if Bluetooth was switched off between the check in [connectToRemote] and the attempt; or keep
     * the error visible for failures an immediate restart would only repeat.
     */
    private fun onConnectionFailed(reason: FailureReason) {
        when (FailureStopPolicy.actionFor(reason)) {
            FailureStopPolicy.FailureAction.STOP -> {
                Log.i(TAG, "Service: gave up ($reason), stopping; the next presence event connects again")
                stopSelfSafely()
            }
            FailureStopPolicy.FailureAction.WAIT_FOR_BLUETOOTH -> waitForBluetooth()
            FailureStopPolicy.FailureAction.KEEP_ERROR_VISIBLE ->
                Log.w(TAG, "Service: gave up ($reason), staying up: a restart would fail the same way")
        }
    }

    /**
     * The only way this service stops itself: always with the `startId` of the last [onStartCommand].
     *
     * Most stop decisions are made off the main thread (the connection-state collector runs on
     * [Dispatchers.Default]), so a new start command — a fresh `BLE_APPEARED` — can be delivered while one is
     * being taken. `stopSelf()` without an ID would discard that start as well and leave the service down until
     * the system happens to send another presence event, which it does not do while the remote stays present.
     * [Service.stopSelf] with an ID stops nothing if a newer start command has arrived in the meantime; the
     * service then keeps running with the newer one, which is exactly what is wanted.
     *
     * Before the first [onStartCommand] there is no ID to pass ([NO_START_ID]) — nothing can have been
     * superseded then, so the unconditional stop is correct.
     */
    private fun stopSelfSafely() {
        val startId = lastStartId
        if (startId == NO_START_ID) stopSelf() else stopSelf(startId)
    }

    /**
     * Runs on the manager's BLE thread; returns quickly (see [PacketListener]).
     *
     * While a confirmation is running ([dispatchAllowed]) the first valid packet ends it: any button counts,
     * including one this version maps no action to, because the press only says "this is my remote". It is
     * shown as the last command like every other press but deliberately not executed — nobody expects the
     * remote to skip a track while being paired. Only this connection exists at that moment and it belongs to
     * the association being confirmed ([connectAllowed]), so the press cannot come from another remote.
     */
    private fun onPacket(packet: ByteArray) {
        when (val result = CratoniProtocolParser.parse(packet)) {
            is ParseResult.Invalid ->
                Log.w(TAG, "Service: invalid packet ${result.reason} [${result.rawHex}], not dispatched")
            is ParseResult.Command -> {
                Log.i(TAG, "Service: parsed ${result.command} [${result.rawHex}]")
                RemoteStatus.onCommand(result.command)
                val state = RemoteStatus.state.value
                val pending = state.pendingConfirmation
                if (pending != null) {
                    // Too late is not a confirmation. The deadline is checked here as well as in the timer,
                    // because a press can arrive in the same moment the time runs out, and then the time wins.
                    if (pending.isExpired(SystemClock.elapsedRealtime())) {
                        Log.w(TAG, "Service: ${result.command} arrived after the confirmation deadline")
                        failConfirmation(pending.associationId, ConfirmationOutcome.TIMED_OUT)
                        return
                    }
                    Log.i(TAG, "Service: ${result.command} confirms the association, shown but not dispatched")
                    // The state first, on this thread: it closes the gate for the next packet and ends the
                    // countdown that would otherwise take the association back at the deadline.
                    RemoteStatus.onConfirmed(pending.associationId)
                    // From here on this is the user's remote: the helmet connection that was held back until
                    // the confirmation can be opened (idempotent, does not block), and the battery level that
                    // was read during the confirmation can be shown (the flow would not emit it again).
                    helmet.connect()
                    RemoteStatus.onBattery(bleManager?.battery?.value)
                    // In [criticalScope] on Dispatchers.IO: persisting writes the preferences file, which must
                    // not happen on this thread (it delivers the button packets), and it must survive this
                    // service being destroyed in the same moment — the screen already says "confirmed".
                    criticalScope.launch {
                        val confirmed = persistConfirmation(state, confirmedRemote)
                        Log.i(TAG, "Service: confirmation of association $confirmed persisted")
                    }
                    return
                }
                val dispatcher = dispatcher ?: return
                when (val dispatchResult = dispatcher.dispatch(result.command)) {
                    is DispatchResult.Failed ->
                        Log.w(TAG, "Service: dispatch failed: ${dispatchResult.command}", dispatchResult.cause)
                    else -> Log.i(TAG, "Service: dispatch $dispatchResult")
                }
            }
        }
    }

    /**
     * Publishes the helmet's connection state and clears the remembered light state whenever a fresh SPP
     * connection stands — the helmet was unreachable until then, so its lights are off and the old state is
     * worthless. Nothing is sent to the helmet for that ([HelmetLightExecutor.reset]).
     */
    private fun observeHelmet() {
        scope.launch {
            helmet.connectionState.collect { state ->
                RemoteStatus.onHelmetState(state)
                if (state == HelmetConnectionState.Connected) lights.reset()
            }
        }
    }

    private fun observeStatusForNotification() {
        scope.launch {
            RemoteStatus.state
                .map { remoteNotificationText(it) }
                .distinctUntilChanged()
                .collect { text -> postNotification(text, "state change") }
        }
    }

    /**
     * `startForeground` with [ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE].
     *
     * Fails if `BLUETOOTH_CONNECT` is missing ([SecurityException]) or if the start is not allowed, e.g.
     * outside the companion device exemption ([android.app.ForegroundServiceStartNotAllowedException] and
     * [android.app.ForegroundServiceTypeException], both [IllegalStateException]). The caller then stops the
     * service instead of letting the system kill it.
     */
    private fun startForegroundSafely(): Boolean {
        val text = remoteNotificationText(RemoteStatus.state.value)
        val notification = buildNotification(text)
        return try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            // This line is now standing, so the first emission of the flow — which carries the same state —
            // must not post it a second time.
            synchronized(notificationLock) { lastPostedText = text }
            Log.i(TAG, "Service: foreground (connectedDevice), notification line '$text'")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Service: startForeground refused, BLUETOOTH_CONNECT missing?", e)
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Service: startForeground not allowed", e)
            false
        }
    }

    /**
     * Low importance: the notification is required for the foreground service, it must not interrupt.
     *
     * Called again on every language change ([refreshLocalisedText]), because name and description come from
     * string resources and are visible in the system settings and under the notification. Creating a channel
     * that already exists is the documented way to do exactly that — [NotificationManager.createNotificationChannel]:
     * "This can also be used … to update an existing channel's name, description, group, and/or importance.
     * The name and description should only be changed if the locale changes … All other fields are ignored
     * for channels that already exist." The importance is only taken over when it is *lower* than the current
     * one and the user has altered no setting on the channel; this call passes the same one every time, so a
     * sound, a vibration or an importance the user chose is kept either way.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_remote_notification)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent())
        .build()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = CratoniBleManager.TAG

        /** The remote of [EXTRA_ASSOCIATION_ID] is present; connect. */
        const val ACTION_DEVICE_APPEARED = "de.werklade.cratoniremote.action.DEVICE_APPEARED"

        /**
         * Connect to the chosen remote of [EXTRA_ASSOCIATION_ID] so its confirmation press can be received.
         * Same connection path as [ACTION_DEVICE_APPEARED], only without a presence event behind it and for an
         * association that is not confirmed yet.
         */
        const val ACTION_CONFIRM_ASSOCIATION = "de.werklade.cratoniremote.action.CONFIRM_ASSOCIATION"

        /** Association ID of `android.companion.AssociationInfo`; [NO_ASSOCIATION_ID] means "the only one". */
        const val EXTRA_ASSOCIATION_ID = "de.werklade.cratoniremote.extra.ASSOCIATION_ID"

        const val NO_ASSOCIATION_ID = -1

        /** Longest step the confirmation timer sleeps before measuring the deadline again. */
        private const val CONFIRMATION_CHECK_INTERVAL_MS = 1_000L

        private const val CHANNEL_ID = "remote_connection"
        private const val NOTIFICATION_ID = 1
        private const val UNKNOWN_BLUETOOTH_STATE = -1

        /** No `startId` yet; the system numbers them from 1 (see [stopSelfSafely]). */
        private const val NO_START_ID = 0

        /**
         * Starts the service in the foreground for the remote of [associationId]. Allowed from the background
         * because of `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`; if the system refuses it
         * anyway, that is logged and nothing happens (the next presence event tries again).
         */
        fun start(context: Context, associationId: Int) {
            val intent = Intent(context, RemoteConnectionService::class.java)
                .setAction(ACTION_DEVICE_APPEARED)
                .putExtra(EXTRA_ASSOCIATION_ID, associationId)
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Service: startForegroundService refused", e)
            }
        }

        /**
         * Starts the service so the confirmation press on the chosen remote of [associationId] can be
         * received. Called by the UI while it is in the foreground, which is why no companion exemption is
         * needed here; if the system refuses the start anyway, that is logged and the confirmation runs into
         * its timeout, which takes the association back.
         */
        fun confirm(context: Context, associationId: Int) {
            val intent = Intent(context, RemoteConnectionService::class.java)
                .setAction(ACTION_CONFIRM_ASSOCIATION)
                .putExtra(EXTRA_ASSOCIATION_ID, associationId)
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Service: startForegroundService for the confirmation refused", e)
            }
        }

        /** Stops the service; harmless if it is not running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteConnectionService::class.java))
        }
    }
}
