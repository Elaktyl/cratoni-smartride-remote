package de.werklade.cratoniremote.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import de.werklade.cratoniremote.protocol.RemoteStatusFrameParser
import java.io.File
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GATT client for exactly one CRATONI SMARTRIDE 1.2 remote (docs/architektur.md §3 "Betrieb", §5).
 *
 * Delivers only bytes and state: FFF4 button packets go to [packetListener], none of them is parsed or executed
 * here (§8). The single exception is the FFF4 status frame of a *read*, which is no button packet, never reaches
 * the listener and carries the battery measurement this class publishes as [battery]
 * ([RemoteStatusFrameParser], pure Kotlin).
 *
 * Sequence, strictly one GATT operation at a time:
 * `connectGatt(autoConnect=false, TRANSPORT_LE)` → [BleConnectionState.Connecting] → link up →
 * [BleConnectionState.DiscoveringServices] → FFF0/FFF4/CCCD present (otherwise
 * [FailureReason.DeviceUnsuitable]) → [BleConnectionState.EnablingNotifications] (FFF4 CCCD write) → successful
 * `onDescriptorWrite` → [BleConnectionState.Connected] → Battery Level 2A19 CCCD write (failure: logged only,
 * [battery] stays `null`) → PnP ID 2A50 read once per connection (logged as hex only) → FFF4 read (status frame)
 * and from then on every [STATUS_POLL_INTERVAL_MS] again while the connection stands.
 *
 * Neither read can fail the connection: any failure (status ≠ 0, timeout, operation not started) is logged and
 * the connection stays [BleConnectionState.Connected]; a status frame that cannot be parsed leaves the last
 * known [battery] value untouched.
 *
 * Unexpected disconnects, GATT errors (e.g. status 133) and timeouts close the [BluetoothGatt] immediately and
 * are retried according to [reconnectPolicy]; afterwards [BleConnectionState.Failed]. After [disconnect] nothing
 * is retried. At most one [BluetoothGatt] exists at any time.
 *
 * Threading: every GATT call, GATT callback, timeout, reconnect and status poll runs on one private
 * [HandlerThread] (callbacks are delivered through its [Handler] resp. an [Executor] posting to it). All mutable
 * state is confined to that thread, so no locks are needed and GATT operations cannot overlap. The public methods
 * only post to it and are therefore thread-safe and non-blocking; [connectionState] and [battery] are thread-safe
 * [StateFlow]s.
 *
 * Permissions: `BLUETOOTH_CONNECT` is checked with an explicit `checkSelfPermission` in every method that makes a
 * GATT call (lint's MissingPermission analysis only sees checks in the same method); a [SecurityException] from a
 * revocation in between is caught as well. Both end in [FailureReason.MissingPermission].
 *
 * Not covered in step 4: Bluetooth on/off broadcasts. If Bluetooth is switched off while connected, the manager
 * relies on the stack reporting the disconnect; the reconnect attempt then ends in
 * [FailureReason.BluetoothDisabled].
 *
 * The instance owns a thread until [close] is called.
 *
 * @param packetListener receives every FFF4 notification, see [PacketListener] for the thread contract
 */
class CratoniBleManager(
    context: Context,
    private val packetListener: PacketListener,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) {

    private val appContext: Context = context.applicationContext
    private val bluetoothManager: BluetoothManager? = appContext.getSystemService(BluetoothManager::class.java)

    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)
    private val callbackExecutor = Executor { command ->
        if (!handler.post(command)) Log.w(TAG, "BLE thread stopped, GATT callback dropped")
    }
    private val closed = AtomicBoolean(false)
    private val timeoutToken = Any()
    private val reconnectToken = Any()
    private val statusPollToken = Any()

    /**
     * True in the debug build. `BuildConfig.DEBUG` is not available: `buildFeatures.buildConfig` is off (AGP
     * default), and turning it on would mean changing `app/build.gradle.kts`. `FLAG_DEBUGGABLE` is the same
     * distinction at runtime — it is set for the debug build and cleared for the release build.
     */
    private val debugBuild = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)

    /** Current connection state; see [BleConnectionState]. */
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _battery = MutableStateFlow<RemoteBattery?>(null)

    /**
     * Last valid battery measurement, `null` while unknown or not connected.
     *
     * Source is the FFF4 status frame (percent and voltage, see [readFff4Status]); a Battery Level 0x2A19
     * notification would fill the same flow without a voltage. An unreadable or implausible value leaves the
     * previous measurement in place.
     */
    val battery: StateFlow<RemoteBattery?> = _battery.asStateFlow()

    // Confined to the BLE thread.
    private var device: BluetoothDevice? = null
    private var currentGatt: BluetoothGatt? = null
    private var step = Step.NONE
    private var attemptsUsed = 0
    private var connectedAtMs: Long? = null
    private var discoveryStartedAtMs = 0L

    /** Whether the quick retry of [scheduleStatusRetry] has been used in the current poll interval. */
    private var statusRetryUsed = false

    /** Created on first use in the debug build, see [recordFff4Probe]; shut down in [close]. */
    private var probeWriter: ExecutorService? = null

    /**
     * Connects to [device].
     *
     * Ignored (logged only) for the address that is already in use: while a connection exists or is being set up,
     * and while a reconnect for it is already scheduled ([BleConnectionState.Reconnecting]). In the latter case the
     * scheduled attempt and the attempt counter stay untouched, so repeated calls — e.g. from recurring companion
     * device presence events — cannot start an extra `connectGatt` or extend [ReconnectPolicy.maxAttempts].
     *
     * A different device ends the current connection resp. pending attempt, resets the attempt counter and starts
     * over.
     */
    fun connect(device: BluetoothDevice) = runOnBleThread("connect") { connectInternal(device) }

    /** Closes the connection and cancels pending timeouts and reconnect attempts. Idempotent. */
    fun disconnect() = runOnBleThread("disconnect") { disconnectInternal() }

    /**
     * [disconnect] and stop the BLE thread. Idempotent; afterwards [connect] and [disconnect] are ignored (logged).
     * The state flows keep their last values ([BleConnectionState.Disconnected], `null`).
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Runs before the quit takes effect: quitSafely() still delivers messages that are already due.
        handler.post {
            guarded("close") { disconnectInternal() }
            // shutdown() lets already queued probe writes finish, it does not interrupt them.
            probeWriter?.shutdown()
            probeWriter = null
            Log.i(TAG, "BLE manager closed")
        }
        thread.quitSafely()
    }

    private fun runOnBleThread(operation: String, block: () -> Unit) {
        val posted = !closed.get() && handler.post {
            // Re-check on the BLE thread: a task posted concurrently with close() must not open a new GATT client.
            if (closed.get()) Log.w(TAG, "$operation ignored: manager closed") else guarded(operation, block)
        }
        if (!posted) Log.w(TAG, "$operation ignored: manager closed")
    }

    private fun connectInternal(target: BluetoothDevice) {
        if (device?.address == target.address) {
            val state = _connectionState.value
            // A scheduled reconnect keeps its delay and its attempt counter; only another device restarts anything.
            if (currentGatt != null || state is BleConnectionState.Reconnecting) {
                Log.i(TAG, "connect ignored: $state with ${BleLog.mac(target.address)}")
                return
            }
        }
        cancelScheduled()
        closeGatt(disconnectFirst = true)
        device = target
        attemptsUsed = 0
        connectedAtMs = null
        Log.i(TAG, "connect ${BleLog.mac(target.address)}")
        startAttempt()
    }

    private fun disconnectInternal() {
        cancelScheduled()
        closeGatt(disconnectFirst = true)
        device = null
        attemptsUsed = 0
        connectedAtMs = null
        setState(BleConnectionState.Disconnected)
    }

    private fun startAttempt() {
        val target = device ?: return
        closeGatt(disconnectFirst = true) // never two GATT clients; normally already closed
        val adapter = bluetoothManager?.adapter ?: return handleFailure(FailureReason.BluetoothAdapterMissing)
        if (appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return failMissingPermission("connectGatt")
        }
        if (!adapter.isEnabled) return handleFailure(FailureReason.BluetoothDisabled)

        setState(BleConnectionState.Connecting)
        step = Step.CONNECTING
        Log.i(TAG, "connectGatt ${BleLog.mac(target.address)} (autoConnect=false, TRANSPORT_LE)")
        val gatt: BluetoothGatt? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            Api37Impl.connectGatt(target, callbackExecutor, gattCallback)
        } else {
            // API 36: all connectGatt(Context, …) overloads are deprecated since API 37.0, and their replacement
            // above only exists since 37.0 (android-37.0 api-versions.xml). With minSdk 36 there is no
            // non-deprecated alternative on API 36, hence the suppression, limited to this declaration.
            @Suppress("DEPRECATION")
            val legacyGatt = target.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler,
            )
            legacyGatt
        }
        if (gatt == null) {
            Log.w(TAG, "connectGatt returned null")
            return handleFailure(FailureReason.OperationNotStarted(GattStep.CONNECT))
        }
        currentGatt = gatt
        scheduleTimeout(Step.CONNECTING, CONNECT_TIMEOUT_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) =
            guarded("onConnectionStateChange") { handleConnectionStateChange(gatt, status, newState) }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            guarded("onServicesDiscovered") { handleServicesDiscovered(gatt, status) }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            guarded("onDescriptorWrite") { handleDescriptorWrite(gatt, descriptor, status) }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = guarded("onCharacteristicRead") { handleCharacteristicRead(gatt, characteristic, value, status) }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = guarded("onCharacteristicChanged") { handleCharacteristicChanged(gatt, characteristic, value) }
    }

    private fun handleConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (gatt !== currentGatt) {
            Log.d(TAG, "Stale onConnectionStateChange ignored (status=$status, newState=$newState)")
            return
        }
        Log.i(TAG, "onConnectionStateChange status=$status newState=${connectionStateName(newState)} step=$step")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> when {
                status != BluetoothGatt.GATT_SUCCESS ->
                    handleFailure(FailureReason.GattError(GattStep.CONNECT, status))
                step != Step.CONNECTING -> Log.w(TAG, "STATE_CONNECTED in step $step ignored")
                else -> startServiceDiscovery(gatt)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                val reason = if (step == Step.CONNECTING && status != BluetoothGatt.GATT_SUCCESS) {
                    FailureReason.GattError(GattStep.CONNECT, status)
                } else {
                    FailureReason.ConnectionLost(status)
                }
                handleFailure(reason, linkDown = true)
            }
        }
    }

    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        cancelTimeout()
        setState(BleConnectionState.DiscoveringServices)
        step = Step.DISCOVERING_SERVICES
        if (appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return failMissingPermission("discoverServices")
        }
        discoveryStartedAtMs = SystemClock.elapsedRealtime()
        if (!gatt.discoverServices()) {
            Log.w(TAG, "discoverServices returned false")
            return handleFailure(FailureReason.OperationNotStarted(GattStep.DISCOVER_SERVICES))
        }
        scheduleTimeout(Step.DISCOVERING_SERVICES, DISCOVERY_TIMEOUT_MS)
    }

    private fun handleServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (gatt !== currentGatt || step != Step.DISCOVERING_SERVICES) {
            Log.d(TAG, "Unexpected onServicesDiscovered ignored (status=$status, step=$step)")
            return
        }
        cancelTimeout()
        Log.i(TAG, "Service discovery: status=$status after ${SystemClock.elapsedRealtime() - discoveryStartedAtMs} ms")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            return handleFailure(FailureReason.GattError(GattStep.DISCOVER_SERVICES, status))
        }
        val service = gatt.getService(GattUuids.SERVICE_FFF0)
            ?: return handleFailure(FailureReason.DeviceUnsuitable("service FFF0"))
        val packets = service.getCharacteristic(GattUuids.CHARACTERISTIC_FFF4)
            ?: return handleFailure(FailureReason.DeviceUnsuitable("characteristic FFF4"))
        if (packets.getDescriptor(GattUuids.DESCRIPTOR_CCCD) == null) {
            return handleFailure(FailureReason.DeviceUnsuitable("CCCD of FFF4"))
        }

        setState(BleConnectionState.EnablingNotifications)
        startEnableNotifications(gatt, packets, Step.ENABLING_FFF4) {
            handleFailure(FailureReason.OperationNotStarted(GattStep.ENABLE_NOTIFICATIONS))
        }
    }

    /**
     * `setCharacteristicNotification` plus CCCD write with [BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE].
     * If the write was started, `onDescriptorWrite` or the timeout follows in [waitStep]. Otherwise
     * [onNotStarted] runs — except for a missing permission, which ends the connection directly.
     */
    private fun startEnableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        waitStep: Step,
        onNotStarted: () -> Unit,
    ) {
        val name = nameOf(characteristic.uuid)
        if (appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return failMissingPermission("enable notifications for $name")
        }
        val cccd = characteristic.getDescriptor(GattUuids.DESCRIPTOR_CCCD)
        if (cccd == null) {
            Log.w(TAG, "$name has no CCCD")
            return onNotStarted()
        }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.w(TAG, "setCharacteristicNotification($name) returned false")
            return onNotStarted()
        }
        val result = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.w(TAG, "writeDescriptor(CCCD of $name) returned $result")
            return onNotStarted()
        }
        step = waitStep
        scheduleTimeout(waitStep, DESCRIPTOR_WRITE_TIMEOUT_MS)
    }

    private fun handleDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (gatt !== currentGatt) {
            Log.d(TAG, "Stale onDescriptorWrite ignored (status=$status)")
            return
        }
        val uuid: UUID? = descriptor.characteristic?.uuid
        when {
            step == Step.ENABLING_FFF4 && uuid == GattUuids.CHARACTERISTIC_FFF4 -> {
                cancelTimeout()
                Log.i(TAG, "CCCD write FFF4: status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    return handleFailure(FailureReason.GattError(GattStep.ENABLE_NOTIFICATIONS, status))
                }
                step = Step.NONE
                connectedAtMs = SystemClock.elapsedRealtime()
                setState(BleConnectionState.Connected)
                startBatteryNotifications(gatt)
            }
            step == Step.ENABLING_BATTERY && uuid == GattUuids.CHARACTERISTIC_BATTERY_LEVEL -> {
                cancelTimeout()
                step = Step.NONE
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "CCCD write Battery Level 2A19: status=0")
                } else {
                    Log.w(TAG, "CCCD write Battery Level 2A19: status=$status, battery level stays unknown")
                }
                readPnpId(gatt)
            }
            else -> Log.w(TAG, "Unexpected onDescriptorWrite for ${nameOf(uuid)} in step $step (status=$status)")
        }
    }

    private fun startBatteryNotifications(gatt: BluetoothGatt) {
        val battery = gatt.getService(GattUuids.SERVICE_FFF0)?.getCharacteristic(GattUuids.CHARACTERISTIC_BATTERY_LEVEL)
        if (battery == null) {
            Log.w(TAG, "Battery Level 2A19 not found in FFF0, battery level stays unknown")
            return readPnpId(gatt)
        }
        startEnableNotifications(gatt, battery, Step.ENABLING_BATTERY) {
            Log.w(TAG, "Battery Level notifications not started, battery level stays unknown")
            readPnpId(gatt)
        }
    }

    /**
     * Diagnosis only (docs/architektur.md §4): the PnP ID is logged, no logic depends on it.
     * Every outcome continues with [readFff4Status], so the two reads stay strictly sequential.
     */
    private fun readPnpId(gatt: BluetoothGatt) {
        if (appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return failMissingPermission("read PnP ID")
        }
        val pnpId = gatt.getService(GattUuids.SERVICE_DEVICE_INFORMATION)?.getCharacteristic(GattUuids.CHARACTERISTIC_PNP_ID)
        if (pnpId == null) {
            Log.i(TAG, "PnP ID 2A50 not found")
            return readFff4Status(gatt)
        }
        if (!gatt.readCharacteristic(pnpId)) {
            Log.w(TAG, "readCharacteristic(PnP ID 2A50) returned false")
            return readFff4Status(gatt)
        }
        step = Step.READING_PNP_ID
        scheduleTimeout(Step.READING_PNP_ID, READ_TIMEOUT_MS)
    }

    /**
     * Reads the FFF4 status frame (docs/architektur.md §4, §11): the remote answers a READ on FFF4 with three bytes
     * `[percent] [voltage high] [voltage low]`, the manufacturer app does the same read right after connecting.
     * The bytes are logged as hex, appended to [Fff4ProbeLog] in the debug build and published as [battery].
     *
     * Runs once right after the connection is up and then every [STATUS_POLL_INTERVAL_MS] ([scheduleStatusPoll]);
     * every outcome — success, GATT error, read not started, timeout — schedules the next attempt, so the poll
     * cannot die silently while the connection stands. A read that answered with a button packet instead of the
     * status frame is repeated once after [STATUS_RETRY_DELAY_MS] ([scheduleStatusRetry]).
     *
     * Runs only after the FFF4 CCCD write succeeded, so notifications are already enabled and stay enabled: a READ
     * transports the characteristic value, it does not touch the CCCD. A button packet arriving meanwhile is a
     * notification and is delivered to [handleCharacteristicChanged], which neither reads nor writes [step].
     */
    private fun readFff4Status(gatt: BluetoothGatt) {
        if (appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return failMissingPermission("read FFF4 status frame")
        }
        val packets = gatt.getService(GattUuids.SERVICE_FFF0)?.getCharacteristic(GattUuids.CHARACTERISTIC_FFF4)
        if (packets == null) {
            // Cannot happen after a successful discovery (FFF4 is required there); handled like any other miss.
            Log.i(TAG, "FFF4 not found for the status frame read")
            return scheduleStatusPoll()
        }
        if (!gatt.readCharacteristic(packets)) {
            Log.w(TAG, "readCharacteristic(FFF4) returned false, no status frame this time")
            return scheduleStatusPoll()
        }
        step = Step.READING_FFF4_STATUS
        scheduleTimeout(Step.READING_FFF4_STATUS, READ_TIMEOUT_MS)
    }

    /**
     * Schedules the next FFF4 status read in [STATUS_POLL_INTERVAL_MS]. Replaces an already scheduled one, so
     * there is at most one pending poll; [cancelScheduled] removes it on disconnect, failure and [close], which
     * is why no callback can outlive the connection or the BLE thread.
     *
     * [Handler.postDelayed] runs on [SystemClock.uptimeMillis], which stands still in deep sleep, so the poll can
     * fire noticeably late while the device is suspended. That is acceptable and deliberate: the value is only
     * displayed, and waking the device every five minutes with an [android.app.AlarmManager] would cost far more
     * battery than the measurement is worth.
     */
    private fun scheduleStatusPoll() {
        handler.removeCallbacksAndMessages(statusPollToken)
        // Every regular interval may use its own quick retry again; the flag only limits repeats within one.
        statusRetryUsed = false
        handler.postDelayed({ guarded("status poll") { pollFff4Status() } }, statusPollToken, STATUS_POLL_INTERVAL_MS)
    }

    /**
     * Reads the status frame again in [STATUS_RETRY_DELAY_MS] after the read returned a button packet instead of
     * the status frame — pressing a button is exactly when a read is likely to collide with one, and five minutes
     * of a stale value for a moment of bad luck is a poor trade.
     *
     * At most one retry per poll interval ([statusRetryUsed], reset in [scheduleStatusPoll]), so a remote that
     * keeps answering with button packets cannot turn the poll into a two second loop. Uses [statusPollToken], so
     * there is still at most one pending callback and [cancelScheduled] (disconnect, failure, [close]) removes
     * this one as well.
     */
    private fun scheduleStatusRetry() {
        if (statusRetryUsed) {
            Log.i(TAG, "FFF4 status retry already used, back to the ${STATUS_POLL_INTERVAL_MS} ms poll")
            return scheduleStatusPoll()
        }
        handler.removeCallbacksAndMessages(statusPollToken)
        statusRetryUsed = true
        Log.i(TAG, "FFF4 status frame missed, retrying once in $STATUS_RETRY_DELAY_MS ms")
        handler.postDelayed({ guarded("status retry") { pollFff4Status() } }, statusPollToken, STATUS_RETRY_DELAY_MS)
    }

    /** Reads the status frame again if the connection still stands and no other GATT operation is pending. */
    private fun pollFff4Status() {
        val gatt = currentGatt
        if (gatt == null || _connectionState.value != BleConnectionState.Connected) {
            Log.i(TAG, "FFF4 status poll dropped: no live connection (step=$step)")
            return
        }
        if (step != Step.NONE) {
            Log.i(TAG, "FFF4 status poll postponed: step=$step")
            return scheduleStatusPoll()
        }
        readFff4Status(gatt)
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        val uuid = characteristic.uuid
        when {
            gatt !== currentGatt ->
                Log.d(TAG, "Stale onCharacteristicRead for ${nameOf(uuid)} ignored (status=$status)")
            step == Step.READING_PNP_ID && uuid == GattUuids.CHARACTERISTIC_PNP_ID -> {
                cancelTimeout()
                step = Step.NONE
                Log.i(TAG, "PnP ID 2A50: status=$status value=[${BleHex.format(value)}]")
                readFff4Status(gatt)
            }
            step == Step.READING_FFF4_STATUS && uuid == GattUuids.CHARACTERISTIC_FFF4 -> {
                cancelTimeout()
                step = Step.NONE
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Read FFF4: status=$status, no status frame this time")
                    scheduleStatusPoll()
                } else if (handleFff4StatusFrame(value)) {
                    scheduleStatusPoll()
                } else {
                    // The read collided with a button press and returned its packet: try once more in a
                    // moment instead of waiting five minutes for a value that was simply unlucky.
                    scheduleStatusRetry()
                }
            }
            uuid == GattUuids.CHARACTERISTIC_FFF4 -> {
                // Answer after the read timeout already fired: the next attempt is scheduled and [step] belongs
                // to whatever runs now, so neither is touched here — only the value itself is still usable, and
                // a measurement a few seconds late is worth more than none.
                Log.w(TAG, "Late onCharacteristicRead for FFF4 in step $step (status=$status)")
                if (status == BluetoothGatt.GATT_SUCCESS) handleFff4StatusFrame(value)
            }
            else -> Log.w(TAG, "Unexpected onCharacteristicRead for ${nameOf(uuid)} in step $step (status=$status)")
        }
    }

    /**
     * Logs the raw status frame, keeps it in [Fff4ProbeLog] (debug build) and publishes it as [battery].
     *
     * The same read returns a button packet (4 or 5 bytes, header `0x0F`) when a button was pressed a moment
     * before, and the value can be implausible for other reasons. [RemoteStatusFrameParser] rejects both; the
     * previous measurement then stays in place, because an old value is more useful than none.
     *
     * @return whether the value was a status frame and [battery] was updated; `false` asks the caller for the
     *   quick retry of [scheduleStatusRetry]
     */
    private fun handleFff4StatusFrame(value: ByteArray): Boolean {
        val hex = BleHex.format(value)
        recordFff4Probe(hex)
        val frame = RemoteStatusFrameParser.parseStatusFrame(value)
        if (frame == null) {
            Log.w(TAG, "FFF4 status frame [$hex] is no status frame (button packet or implausible), value kept")
            return false
        }
        Log.i(TAG, "FFF4 status frame [$hex] = ${frame.batteryPercent} %, ${frame.millivolts} mV")
        _battery.value = RemoteBattery(
            percent = frame.batteryPercent,
            millivolts = frame.millivolts,
            atEpochMs = System.currentTimeMillis(),
        )
        return true
    }

    /**
     * Debug build only: appends the status frame to [Fff4ProbeLog] so the values survive without a PC
     * (logcat is a ring buffer). In the release build nothing is written and no file is created.
     *
     * The write runs on a separate single thread, not on the BLE thread: the BLE thread also delivers the FFF4
     * button notifications to the [packetListener], whose action runs synchronously on it (measured 4–25 ms from
     * packet to action). A flash write can stall far longer than that, and the status frame arrives exactly when
     * the user may already be pressing buttons. One thread also serializes the writes, so two connections in quick
     * succession cannot interleave in the file.
     *
     * Errors are logged only; the connection is never affected.
     */
    private fun recordFff4Probe(hex: String) {
        if (!debugBuild) return
        val line = Fff4ProbeLog.line(System.currentTimeMillis(), ZoneId.systemDefault(), hex)
        val file = File(appContext.filesDir, Fff4ProbeLog.FILE_NAME)
        val writer = probeWriter
            ?: Executors.newSingleThreadExecutor { runnable -> Thread(runnable, PROBE_THREAD_NAME) }
                .also { probeWriter = it }
        try {
            writer.execute {
                // Outside guarded(): this is not the BLE thread, and an escaping exception would kill the process.
                try {
                    Fff4ProbeLog.append(file, line)
                } catch (e: Exception) {
                    Log.w(TAG, "FFF4 probe log not written", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "FFF4 probe log not written: writer already shut down", e)
        }
    }

    private fun handleCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (gatt !== currentGatt) {
            Log.d(TAG, "Stale notification ignored")
            return
        }
        when (characteristic.uuid) {
            GattUuids.CHARACTERISTIC_FFF4 -> deliverPacket(value.copyOf())
            GattUuids.CHARACTERISTIC_BATTERY_LEVEL -> updateBatteryLevel(value)
            else -> Log.d(TAG, "Notification from ${characteristic.uuid} ignored")
        }
    }

    private fun deliverPacket(packet: ByteArray) {
        Log.i(TAG, "FFF4 packet [${BleHex.format(packet)}]")
        try {
            packetListener.onPacket(packet)
        } catch (e: Exception) {
            Log.w(TAG, "PacketListener threw", e)
        }
    }

    /**
     * Battery Level 0x2A19 notification. That characteristic has never sent anything in two weeks of measuring
     * although it is subscribed, so it is no longer expected as a source — but if it ever does, it fills the same
     * [battery] flow, without a voltage (0x2A19 carries a percentage only).
     */
    private fun updateBatteryLevel(value: ByteArray) {
        val raw = BleHex.format(value)
        val percent = BatteryLevel.parse(value)
        if (percent == null) {
            Log.w(TAG, "Battery Level raw=[$raw] invalid (expected exactly 1 byte, 0 to 100), ignored")
            return
        }
        Log.i(TAG, "Battery Level raw=[$raw] = $percent %")
        _battery.value = RemoteBattery(percent = percent, millivolts = null, atEpochMs = System.currentTimeMillis())
    }

    /**
     * Closes the GATT client and either schedules a reconnect or ends in [BleConnectionState.Failed].
     *
     * @param linkDown the stack already reported the link as disconnected, so only `close()` is needed
     */
    private fun handleFailure(reason: FailureReason, linkDown: Boolean = false) {
        val failedStep = step
        val connectedForMs = connectedAtMs?.let { SystemClock.elapsedRealtime() - it }
        cancelScheduled()
        closeGatt(disconnectFirst = !linkDown)
        connectedAtMs = null
        Log.w(TAG, "Failure in step $failedStep: $reason (connected for ${connectedForMs ?: "-"} ms)")

        when (val decision = reconnectPolicy.decide(reason, attemptsUsed, connectedForMs)) {
            is ReconnectDecision.Retry -> {
                attemptsUsed = decision.attempt
                setState(
                    BleConnectionState.Reconnecting(decision.attempt, reconnectPolicy.maxAttempts, decision.delayMs, reason),
                )
                handler.postDelayed({ guarded("reconnect") { startAttempt() } }, reconnectToken, decision.delayMs)
            }
            ReconnectDecision.GiveUp -> {
                device = null
                attemptsUsed = 0
                setState(BleConnectionState.Failed(reason))
            }
        }
    }

    /**
     * Releases the GATT client, if any, and resets [battery]. Never throws. Without `BLUETOOTH_CONNECT`
     * the calls throw [SecurityException]; that is logged, the reference is dropped either way.
     */
    private fun closeGatt(disconnectFirst: Boolean) {
        step = Step.NONE
        if (_battery.value != null) {
            Log.i(TAG, "Battery measurement reset to unknown")
            _battery.value = null
        }
        val gatt = currentGatt ?: return
        currentGatt = null
        if (disconnectFirst) {
            try {
                gatt.disconnect()
            } catch (e: SecurityException) {
                Log.w(TAG, "BluetoothGatt.disconnect() threw SecurityException", e)
            }
        }
        try {
            gatt.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "BluetoothGatt.close() threw SecurityException", e)
        }
        Log.i(TAG, "GATT client closed (disconnect first: $disconnectFirst)")
    }

    private fun scheduleTimeout(waitStep: Step, timeoutMs: Long) {
        handler.removeCallbacksAndMessages(timeoutToken)
        handler.postDelayed({ guarded("timeout") { handleTimeout(waitStep, timeoutMs) } }, timeoutToken, timeoutMs)
    }

    private fun handleTimeout(waitStep: Step, timeoutMs: Long) {
        if (step != waitStep) return
        Log.w(TAG, "Timeout after $timeoutMs ms in step $waitStep")
        when (waitStep) {
            Step.CONNECTING -> handleFailure(FailureReason.Timeout(GattStep.CONNECT))
            Step.DISCOVERING_SERVICES -> handleFailure(FailureReason.Timeout(GattStep.DISCOVER_SERVICES))
            Step.ENABLING_FFF4 -> handleFailure(FailureReason.Timeout(GattStep.ENABLE_NOTIFICATIONS))
            Step.ENABLING_BATTERY -> {
                step = Step.NONE
                Log.w(TAG, "Battery level stays unknown")
                currentGatt?.let { readPnpId(it) }
            }
            Step.READING_PNP_ID -> {
                step = Step.NONE
                currentGatt?.let { readFff4Status(it) }
            }
            Step.READING_FFF4_STATUS -> {
                step = Step.NONE
                scheduleStatusPoll()
            }
            Step.NONE -> Unit
        }
    }

    private fun cancelTimeout() = handler.removeCallbacksAndMessages(timeoutToken)

    private fun cancelScheduled() {
        handler.removeCallbacksAndMessages(timeoutToken)
        handler.removeCallbacksAndMessages(reconnectToken)
        handler.removeCallbacksAndMessages(statusPollToken)
        statusRetryUsed = false
    }

    /** Called when the explicit `checkSelfPermission` right before a GATT call failed. */
    private fun failMissingPermission(operation: String) {
        Log.w(TAG, "$operation: BLUETOOTH_CONNECT not granted")
        handleFailure(FailureReason.MissingPermission)
    }

    /** A [SecurityException] (permission revoked between check and call) ends in [FailureReason.MissingPermission]. */
    private inline fun guarded(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            Log.w(TAG, "$operation: SecurityException", e)
            handleFailure(FailureReason.MissingPermission)
        }
    }

    private fun setState(newState: BleConnectionState) {
        val oldState = _connectionState.value
        if (oldState == newState) return
        Log.i(TAG, "State: $oldState -> $newState")
        _connectionState.value = newState
    }

    private fun connectionStateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        else -> state.toString()
    }

    private fun nameOf(uuid: UUID?): String = when (uuid) {
        GattUuids.CHARACTERISTIC_FFF4 -> "FFF4"
        GattUuids.CHARACTERISTIC_BATTERY_LEVEL -> "Battery Level 2A19"
        GattUuids.CHARACTERISTIC_PNP_ID -> "PnP ID 2A50"
        else -> uuid.toString()
    }

    /**
     * The only place where API 37 types appear. The enclosing class therefore stays verifiable on API 36:
     * a class is verified when it is first loaded, and this one is only loaded behind the `SDK_INT` check in
     * [startAttempt].
     */
    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private object Api37Impl {

        /**
         * `connectGatt` with the settings object that replaces the deprecated overloads. Automatic MTU negotiation
         * off, matching the API 36 path (on API 37 the deprecated overloads delegate with
         * `setAutomaticMtuEnabled(false)`); packets are at most 5 bytes. [executor] delivers the GATT callbacks
         * on the BLE thread.
         *
         * The permission is checked by the caller right before the call (see the class KDoc on lint).
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun connectGatt(
            device: BluetoothDevice,
            executor: Executor,
            callback: BluetoothGattCallback,
        ): BluetoothGatt? = device.connectGatt(
            BluetoothGattConnectionSettings.Builder()
                .setAutoConnectEnabled(false)
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .setAutomaticMtuEnabled(false)
                .build(),
            executor,
            callback,
        )
    }

    /** Pending GATT operation; [NONE] if none is pending. */
    private enum class Step {
        NONE,
        CONNECTING,
        DISCOVERING_SERVICES,
        ENABLING_FFF4,
        ENABLING_BATTERY,
        READING_PNP_ID,
        READING_FFF4_STATUS,
    }

    companion object {
        const val TAG = "CratoniRemote"
        private const val THREAD_NAME = "CratoniBle"
        private const val PROBE_THREAD_NAME = "CratoniProbeLog"

        /**
         * Direct connection (`autoConnect=false`) to an advertising remote normally takes a few seconds at most
         * (not measured); 15 s tolerates slow advertising intervals and still ends before the stack's own direct
         * connect timeout, which is commonly reported as about 30 s (not verified).
         */
        const val CONNECT_TIMEOUT_MS = 15_000L

        /** Measured about 0.9 s with nRF Connect; 10 s leaves room for an uncached first discovery. */
        const val DISCOVERY_TIMEOUT_MS = 10_000L

        /**
         * One ATT write normally completes within a few connection intervals; waiting for the ATT transaction
         * timeout of 30 s (Bluetooth Core spec) would only delay the reconnect.
         */
        const val DESCRIPTOR_WRITE_TIMEOUT_MS = 5_000L

        /** PnP ID and FFF4 status frame read, same reasoning as [DESCRIPTOR_WRITE_TIMEOUT_MS]. */
        const val READ_TIMEOUT_MS = 5_000L

        /**
         * Interval of the FFF4 status frame read while connected. Five minutes is far more often than the battery
         * of the remote can realistically change (byte 0 fell in steps of ten over two weeks) and still rare
         * enough to be irrelevant for the connection: one ATT read per five minutes next to the notifications of
         * every button press.
         */
        const val STATUS_POLL_INTERVAL_MS = 5L * 60 * 1000

        /**
         * Delay of the single quick retry after a read answered with a button packet instead of the status frame
         * ([scheduleStatusRetry]). Two seconds are long enough for the button packets of one press to be through
         * (measured 4–25 ms from packet to action) and short enough to stay unnoticeable.
         */
        const val STATUS_RETRY_DELAY_MS = 2_000L
    }
}
