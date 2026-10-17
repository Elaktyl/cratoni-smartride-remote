package de.werklade.cratoniremote.helmet

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.RequiresPermission
import de.werklade.cratoniremote.ble.BleHex
import de.werklade.cratoniremote.ble.BleLog
import de.werklade.cratoniremote.ble.CratoniBleManager
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SPP client for the helmet (docs/architektur.md §13): classic Bluetooth (BR/EDR), one RFCOMM socket, write
 * only.
 *
 * The helmet is a bonded device; it is found by its name ([HELMET_NAME_PREFIX]) among
 * [BluetoothAdapter.getBondedDevices], never by a hard-coded MAC address. No match or more than one match is
 * [HelmetConnectionState.NotFound] — the client then does nothing but log, because guessing which of two
 * devices is meant could open a socket to a stranger's helmet.
 *
 * Only [HelmetFrames] are written, and only the byte sequences measured there. Nothing is read: the helmet does
 * not acknowledge light frames, so no reader thread exists and the [InputStream][java.io.InputStream] of the
 * socket is never touched.
 *
 * **No keep-alive in this version.** The manufacturer app sends `0F 05 00 14` about every ten seconds
 * ([HelmetFrames.KEEP_ALIVE]); whether the helmet keeps the RFCOMM channel open without it is unknown and can
 * only be answered on the device. If the device test shows the link dying after a while, the keep-alive belongs
 * on this client's worker thread (a repeating task, not a coroutine timer, so it shares the thread with the
 * writes).
 *
 * The HFP channel of the same helmet (DLCI 20, the AT command channel of the hands-free profile) is
 * deliberately untouched: this client connects the SPP service record only and never calls anything that would
 * change audio routing, so music and calls keep working as before. That this really is the case is a claim for
 * the device test, not something the code can prove.
 *
 * Threading: one private single-thread [ExecutorService]. [connect] and [send] only queue work on it and return
 * at once, so neither the main thread nor the BLE thread that delivers the remote's button packets ever waits
 * for the blocking [BluetoothSocket.connect] or for a write. [connectionState] is a thread-safe [StateFlow];
 * [socket] is `@Volatile` because [close] closes it from the caller's thread, which is the documented way to
 * abort a blocking `connect()`.
 *
 * Errors are silent by design (decision of the project owner, 26.09.2026): a helmet that is off or out of range
 * produces a log line and a state, no sound and no dialog. An [IOException] while writing closes the socket and
 * ends in [HelmetConnectionState.Failed]; the next button press tries to connect again ([send]).
 *
 * Lifecycle: [connect] as soon as the remote is connected, [disconnect] when Bluetooth is switched off,
 * [close] once when the owner (`service.RemoteConnectionService`) is destroyed. After [close] the instance is
 * dead: the thread is gone and every call only logs.
 *
 * @param namePrefix injectable for tests; production uses [HELMET_NAME_PREFIX]
 */
class HelmetSppClient(
    context: Context,
    private val namePrefix: String = HELMET_NAME_PREFIX,
) : HelmetFrameSender {

    private val appContext: Context = context.applicationContext
    private val bluetoothManager: BluetoothManager? = appContext.getSystemService(BluetoothManager::class.java)

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME)
    }
    private val closed = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow<HelmetConnectionState>(HelmetConnectionState.Disconnected)

    /** Current state of the SPP connection; see [HelmetConnectionState]. */
    val connectionState: StateFlow<HelmetConnectionState> = _connectionState.asStateFlow()

    /** Written on the worker thread, closed by [close] and [disconnect] from any thread, hence `@Volatile`. */
    @Volatile
    private var socket: BluetoothSocket? = null

    /** Worker thread only. */
    private var output: OutputStream? = null

    /**
     * Address of the bonded device [findHelmet] last resolved, `null` until one was resolved at all (no
     * [connect] has run yet, or none matched). Written on the worker thread, read from the main thread by
     * `service.RemoteConnectionService` when a classic Bluetooth ACL event arrives, hence `@Volatile`.
     *
     * It is set even when the following `connect()` fails, which is the case that matters: the helmet is off,
     * so the connection attempt at "remote connected" ends in [HelmetConnectionState.Failed] but the address of
     * the bonded helmet is known from then on.
     */
    @Volatile
    private var resolvedAddress: String? = null

    /**
     * Opens the SPP connection if none is open; idempotent and non-blocking.
     *
     * A second call while connecting or connected does nothing, so it can be called on every "remote
     * connected" event without opening a second socket.
     */
    fun connect() {
        submit("connect") { connectOnWorker() }
    }

    /**
     * Queues [frame] for the standing connection.
     *
     * Returns `false` without sending anything if there is no connection, and then starts a connection attempt
     * so the next press can work — the fallback behind the
     * service's ACL receiver, which opens the connection the moment the helmet is switched on; nothing is
     * scheduled and nothing is retried automatically.
     *
     * [frame] is copied, so the caller may reuse its array.
     */
    override fun send(frame: ByteArray): Boolean {
        val state = _connectionState.value
        if (state != HelmetConnectionState.Connected) {
            Log.i(TAG, "Helmet: not connected ($state), frame [${BleHex.format(frame)}] not sent")
            if (state != HelmetConnectionState.Connecting) connect()
            return false
        }
        val copy = frame.copyOf()
        return submit("send [${BleHex.format(copy)}]") { sendOnWorker(copy) }
    }

    /**
     * Closes socket and streams but keeps the worker thread, so [connect] can be used again; used when
     * Bluetooth is switched off. Non-blocking, like everything else here.
     */
    fun disconnect() {
        Log.i(TAG, "Helmet: disconnecting on request")
        // Closed here and not only on the worker: this is what aborts a blocking connect().
        closeSocket()
        submit("disconnect") { closeOnWorker(HelmetConnectionState.Disconnected) }
    }

    /**
     * Final shutdown: aborts a running [BluetoothSocket.connect], closes socket and streams and stops the
     * worker thread. Idempotent; after it, nothing is sent and no thread or socket is left behind.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeSocket()
        // Queued before shutdown(), so the worker closes the streams it owns before it ends.
        try {
            worker.execute { closeOnWorker(HelmetConnectionState.Disconnected) }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Helmet: close task rejected", e)
        }
        // shutdown(), not shutdownNow(): a write that is already running may finish, nothing is interrupted.
        worker.shutdown()
        _connectionState.value = HelmetConnectionState.Disconnected
        Log.i(TAG, "Helmet: client closed")
    }

    /**
     * Address of the helmet this client talks to, as far as it is known: the bonded device a previous [connect]
     * resolved, `null` before that. No Bluetooth call and no permission needed, so it can be read from a
     * broadcast receiver on the main thread (`service.RemoteConnectionService` compares it with the device of
     * an ACL event, see `service.isHelmetDevice`).
     */
    fun knownAddress(): String? = resolvedAddress

    /** Queues [block]; `false` if the client is closed or the executor refused it. */
    private fun submit(operation: String, block: () -> Unit): Boolean {
        if (closed.get()) {
            Log.w(TAG, "Helmet: $operation ignored, client closed")
            return false
        }
        return try {
            worker.execute {
                // Re-checked on the worker: a task queued concurrently with close() must not open a socket.
                if (closed.get()) Log.w(TAG, "Helmet: $operation skipped, client closed") else block()
            }
            true
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Helmet: $operation rejected, client closed", e)
            false
        }
    }

    /** Worker thread. Blocks in [BluetoothSocket.connect] for as long as the stack needs. */
    private fun connectOnWorker() {
        if (socket != null) {
            Log.d(TAG, "Helmet: connect ignored, a socket is already open (${_connectionState.value})")
            return
        }
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail(HelmetFailure.BLUETOOTH_UNAVAILABLE, "no Bluetooth adapter or Bluetooth is off")
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            fail(HelmetFailure.MISSING_PERMISSION, "BLUETOOTH_CONNECT not granted")
            return
        }
        val device = try {
            findHelmet(adapter)
        } catch (e: SecurityException) {
            fail(HelmetFailure.MISSING_PERMISSION, "bonded devices refused: ${e.message}")
            return
        }
        if (device == null) {
            setState(HelmetConnectionState.NotFound)
            return
        }
        resolvedAddress = device.address
        setState(HelmetConnectionState.Connecting)
        try {
            val opened = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = opened
            opened.connect()
            output = opened.outputStream
            setState(HelmetConnectionState.Connected)
            Log.i(TAG, "Helmet: SPP connected to ${BleLog.mac(device.address)}")
        } catch (e: IOException) {
            closeSocket()
            output = null
            fail(HelmetFailure.CONNECT, "SPP connect failed: ${e.message}")
        } catch (e: SecurityException) {
            closeSocket()
            output = null
            fail(HelmetFailure.MISSING_PERMISSION, "SPP connect refused: ${e.message}")
        }
    }

    /** Worker thread. Writes [frame] sequentially; a failure closes the connection instead of retrying. */
    private fun sendOnWorker(frame: ByteArray) {
        val stream = output
        if (stream == null) {
            Log.w(TAG, "Helmet: no output stream, frame [${BleHex.format(frame)}] dropped")
            return
        }
        try {
            stream.write(frame)
            stream.flush()
            Log.i(TAG, "Helmet: sent [${BleHex.format(frame)}]")
        } catch (e: IOException) {
            Log.w(TAG, "Helmet: write failed, connection is gone", e)
            closeSocket()
            output = null
            fail(HelmetFailure.SEND, "write failed: ${e.message}")
        }
    }

    /** Worker thread. Releases the streams the worker owns and publishes [state]. */
    private fun closeOnWorker(state: HelmetConnectionState) {
        closeSocket()
        output = null
        setState(state)
    }

    /**
     * The one bonded device whose name starts with [namePrefix], or `null` if there is none or more than one.
     *
     * Callers check `BLUETOOTH_CONNECT` (lint only sees a check in the same method, hence the annotation here).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun findHelmet(adapter: BluetoothAdapter): BluetoothDevice? {
        val matches = adapter.bondedDevices.orEmpty().filter { device ->
            device.name?.startsWith(namePrefix, ignoreCase = true) == true
        }
        return when (matches.size) {
            0 -> {
                Log.w(TAG, "Helmet: no bonded device whose name starts with '$namePrefix'")
                null
            }
            1 -> matches.single()
            else -> {
                Log.w(TAG, "Helmet: ${matches.size} bonded devices match '$namePrefix', not choosing one")
                null
            }
        }
    }

    /** Closes the socket from any thread; that is also how a blocking `connect()` is aborted. */
    private fun closeSocket() {
        val open = socket ?: return
        socket = null
        try {
            open.close()
        } catch (e: IOException) {
            Log.w(TAG, "Helmet: closing the socket failed", e)
        }
    }

    private fun setState(state: HelmetConnectionState) {
        if (_connectionState.value == state) return
        Log.i(TAG, "Helmet: state $state")
        _connectionState.value = state
    }

    private fun fail(reason: HelmetFailure, message: String) {
        Log.w(TAG, "Helmet: $message")
        setState(HelmetConnectionState.Failed(reason))
    }

    companion object {

        /**
         * Name prefix of the helmet, e.g. `SMARTRIDE 1.2-B2C3` (docs/architektur.md §13). Matching by name and
         * not by address on purpose: the address identifies one particular helmet of one particular owner and
         * has no place in the source.
         */
        const val HELMET_NAME_PREFIX = "SMARTRIDE"

        /** Well-known Serial Port Profile UUID; the SDP lookup finds the RFCOMM channel behind it. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Same log tag as the rest of the connection code, so one filter shows the whole path. */
        private const val TAG = CratoniBleManager.TAG

        private const val THREAD_NAME = "helmet-spp"
    }
}
