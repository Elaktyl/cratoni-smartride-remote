package de.werklade.cratoniremote.companion

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import de.werklade.cratoniremote.ble.BleLog
import de.werklade.cratoniremote.ble.CratoniBleManager
import de.werklade.cratoniremote.data.RemoteStatus
import de.werklade.cratoniremote.service.RemoteConnectionService
import java.util.Locale
import java.util.concurrent.Executor
import java.util.regex.Pattern

/**
 * Pairing and presence observation through the [CompanionDeviceManager] (docs/architektur.md §3).
 *
 * The dialog offers a **list**: on a group ride several identical `Remote control-XXXX` are in range, and no
 * filter can tell them apart (the name is generic, the MAC is not known beforehand). The user therefore picks
 * one and confirms it with a press on that very remote; without a press the association is taken back
 * ([cancelAssociation]). What the user confirmed, and what is still waiting for a press, is what the app
 * persists ([ConfirmedRemoteStore]) — the system knows all associations, but not which of them the user meant.
 *
 * Source of truth is therefore both together: [CompanionDeviceManager.getMyAssociations] for what exists and
 * [ConfirmedRemoteStore] for which of it counts. [reconcile] brings the two in line on every app start.
 *
 * Flow: [associate] → system dialog (started by the caller from the [IntentSender]) → [associationFromResult]
 * or `onAssociationCreated` → [markPendingConfirmation], then the UI waits for the confirmation press
 * ([de.werklade.cratoniremote.data.PendingConfirmation]) → the service receives the press and persists it
 * ([de.werklade.cratoniremote.service.persistConfirmation]), the UI starts the presence observation
 * ([startObservingPresence]) → the system delivers presence events to [CratoniCompanionService].
 *
 * Not self-managed and without a device profile: the system does the scanning and the presence detection,
 * the app needs neither `BLUETOOTH_SCAN` nor a profile permission.
 *
 * Threading: the framework calls only delegate to the system service and may run on any thread. [reconcile],
 * [markPendingConfirmation], [markConfirmed], [cancelAssociation] and [forget] also write
 * [ConfirmedRemoteStore] and must therefore be called from a background thread, never from the main or BLE
 * thread. Those five run under one process-wide lock ([PAIRING_LOCK]): the UI, the connection service and the
 * reconcile all change the same two preference values and the same associations, from different threads and
 * from different instances of this class, and half of one change mixed into another is exactly how an
 * association ends up without a marker. They are short — a file write and a system call — and the callers
 * are on background dispatchers anyway.
 */
class CompanionSetup(context: Context) {

    private val appContext: Context = context.applicationContext
    private val manager: CompanionDeviceManager? = appContext.getSystemService(CompanionDeviceManager::class.java)
    private val store = ConfirmedRemoteStore(appContext)

    /** `false` if the device has no companion device support at all; then nothing here works. */
    val isSupported: Boolean get() = manager != null

    /** All associations of this app, empty if none or if companion support is missing. */
    fun associations(): List<AssociationInfo> = manager?.myAssociations.orEmpty()

    /**
     * Whether a remote is set up at all. Deliberately unchanged: it answers what the screen asks ("is there
     * something to forget"), including an association that is still waiting for its confirmation press.
     */
    fun isAssociated(): Boolean = associations().isNotEmpty()

    /**
     * The association the user confirmed and that still exists, or `null`.
     *
     * Both halves matter: a stored ID whose association was removed in the system settings must not be
     * connected to, and an association nobody confirmed must not be either. May be called from any thread (see
     * [ConfirmedRemoteStore]).
     */
    fun confirmedAssociationId(): Int? =
        store.confirmedAssociationId()?.takeIf { id -> associations().any { it.id == id } }

    /**
     * Opens the system dialog for choosing the remote.
     *
     * [onIntentSender] receives the [IntentSender] the caller has to launch (an Activity is required, see
     * `ui.MainActivity`); [onAssociationCreated] reports a created association, and so does the activity result
     * of that [IntentSender] — the platform does not specify which of the two comes first, so the caller has to
     * treat both as the same (idempotent) event. [onFailure] is called if the system refuses the request, with
     * the result code of [CompanionDeviceManager] where the platform provides one and `null` otherwise.
     *
     * No [AssociationRequest.Builder.setSingleDevice]: with it the system picks the first match itself and
     * associates it without showing a list, which is exactly what must not happen among identical remotes. The
     * default of the builder is a list the user chooses from.
     *
     * Filter: only the device name pattern [NAME_PATTERN], no [android.bluetooth.le.ScanFilter] on service
     * 0xFFF0 — the remote advertises no service UUIDs (docs/architektur.md §11), so such a filter would hide it
     * from the dialog, and the dialog is the one place where that must not happen. The pattern is as strict as
     * the observed name format allows (`Remote control-` plus four hex digits), which keeps other devices out
     * of a list the user now has to choose from; it still cannot tell two CRATONI remotes apart, which is what
     * the confirmation press is for.
     *
     * [BluetoothLeDeviceFilter.Builder.setRenameFromName] and `setRenameFromBytes` are deliberately not used,
     * although a rename would make the list more readable: both index into the advertised name and into the MAC
     * address inside the system process and can throw there, where the app cannot catch it.
     */
    fun associate(
        executor: Executor,
        onIntentSender: (IntentSender) -> Unit,
        onAssociationCreated: (AssociationInfo) -> Unit,
        onFailure: (Int?, CharSequence?) -> Unit,
    ) {
        val manager = manager
        if (manager == null) {
            Log.e(TAG, "Companion: CompanionDeviceManager unavailable")
            onFailure(null, null)
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(Pattern.compile(NAME_PATTERN))
                    .build(),
            )
            .build()

        Log.i(TAG, "Companion: associate with name pattern $NAME_PATTERN (device list, not a single device)")
        manager.associate(
            request,
            executor,
            object : CompanionDeviceManager.Callback() {

                /**
                 * Both `onFailure` overloads end here, and only the first one of them counts: the platform
                 * documents neither which one it calls nor that it calls only one, and a second report would
                 * undo a state the first one has already led to.
                 */
                private var failureReported = false

                override fun onAssociationPending(intentSender: IntentSender) {
                    Log.i(TAG, "Companion: association dialog pending")
                    onIntentSender(intentSender)
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    Log.i(TAG, "Companion: association ${associationInfo.id} created")
                    onAssociationCreated(associationInfo)
                }

                /** Available since the API this app compiles against; carries the `RESULT_…` code. */
                override fun onFailure(errorCode: Int, error: CharSequence?) = report(errorCode, error)

                override fun onFailure(error: CharSequence?) = report(null, error)

                private fun report(errorCode: Int?, error: CharSequence?) {
                    if (failureReported) {
                        Log.i(TAG, "Companion: further association failure ignored (code $errorCode, $error)")
                        return
                    }
                    failureReported = true
                    Log.w(TAG, "Companion: association failed (code ${errorCode ?: "unknown"}): $error")
                    onFailure(errorCode, error)
                }
            },
        )
    }

    /**
     * The [AssociationInfo] in the result of the association dialog
     * ([CompanionDeviceManager.EXTRA_ASSOCIATION]), or `null` if the result carries none.
     *
     * Only the ID and the display name of it are used later; `AssociationInfo.getAssociatedDevice()` is read
     * right here for the label, because its `ScanResult` only exists immediately after the pairing and is not
     * persisted with the association.
     */
    fun associationFromResult(data: Intent?): AssociationInfo? = data
        ?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)

    /**
     * What to call the chosen remote on screen, or `null` if the system offers nothing usable — then the caller
     * uses a generic name from its string resources.
     *
     * Order: the advertised device name of the scan result (`"Remote control-3D2C"`, the one the user saw in
     * the dialog), then the display name of the association, then the masked MAC address ([BleLog.mac]) whose
     * last two bytes match the suffix of the advertised name. Display only; the identity is the association.
     */
    fun associationLabel(info: AssociationInfo): String? {
        val advertised = info.associatedDevice?.bleDevice?.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
        if (advertised != null) return advertised
        val displayName = info.displayName?.toString()?.takeIf { it.isNotBlank() }
        if (displayName != null) return displayName
        return info.deviceMacAddress?.toString()?.let { BleLog.mac(it) }
    }

    /**
     * Starts the presence observation for [associationId] using the association ID
     * ([ObservingDevicePresenceRequest]; the `String` overload is deprecated).
     *
     * Only ever for the confirmed association: observing every association would let the system start the
     * service for a remote the user never confirmed — someone else's, on a group ride.
     *
     * Called after a confirmed association and every time the UI starts, so an observation that the system
     * dropped is re-established. Repeating it for an already observed association is assumed to be harmless
     * (not documented in the SDK stubs, to be watched in the device test).
     *
     * @return `true` if the call returned without throwing
     */
    fun startObservingPresence(associationId: Int): Boolean {
        val manager = manager ?: return false
        return try {
            manager.startObservingDevicePresence(request(associationId))
            Log.i(TAG, "Companion: observing presence of association $associationId")
            true
        } catch (e: RuntimeException) {
            // The request-based overload documents no checked exception; a refused or unknown association
            // must not crash the UI.
            Log.w(TAG, "Companion: startObservingDevicePresence($associationId) failed", e)
            false
        }
    }

    /**
     * Notes [associationId] as waiting for its confirmation press, **before** the connection for it is
     * started. Only this marker makes an unconfirmed association recognisable after a process death
     * ([PairingReconciler]) — a fellow rider's remote, picked out of the list by mistake, must not survive it.
     *
     * An association that is still marked from an earlier attempt is taken back first: the marker holds one
     * ID, so a second setup would overwrite it and leave the first association behind unmarked.
     *
     * Writes [ConfirmedRemoteStore] and blocks until the marker is on disk, so it belongs on a background
     * thread and the caller has to wait for it before starting the confirmation.
     *
     * @return `false` if the marker could not be written; the caller then takes the association back at once
     *   instead of waiting for a press whose outcome nothing could clean up after a process death.
     */
    fun markPendingConfirmation(associationId: Int): Boolean = synchronized(PAIRING_LOCK) {
        val stale = store.pendingAssociationId()
        if (stale != null && stale != associationId) {
            Log.w(TAG, "Companion: association $stale was still marked as pending, taking it back first")
            cancelAssociation(stale)
        }
        store.markPending(associationId)
    }

    /**
     * The confirmation press arrived: remembers [associationId] as the user's remote. Idempotent, so the UI may
     * write it as well as the service that received the press — whichever of the two is still alive. Writes
     * [ConfirmedRemoteStore] and therefore belongs on a background thread.
     */
    fun markConfirmed(associationId: Int) = synchronized(PAIRING_LOCK) { store.confirm(associationId) }

    /** Opens the preferences file off the main thread; see [ConfirmedRemoteStore.warmUp]. */
    fun warmUp() = store.warmUp()

    /**
     * Whether the one-time adoption of a pairing from a version without the confirmation step has already been
     * settled ([PairingReconciler]). Read from the preferences, so it may be called from any thread — the
     * connection service asks it on the main thread to find out whether a refused association could still
     * become the user's remote through that migration.
     */
    fun isLegacyMigrationDone(): Boolean = store.isLegacyMigrationDone()

    /**
     * Takes a chosen association back: no confirmation press arrived (timeout, cancelled, Bluetooth off) or it
     * is one association too many ([reconcile]).
     *
     * Stops the observation first and disassociates afterwards, so no presence event can arrive for a
     * removed association. What is forgotten afterwards follows [PairingCleanup]: the stored ID and the
     * pending marker only go once the association really is gone — clearing them after a failed
     * `disassociate` would leave an association behind that nothing points to any more. The connection service
     * is stopped unless a confirmed association is left, the same invariant [forget] keeps: no remote, no
     * service.
     *
     * Writes [ConfirmedRemoteStore] and therefore belongs on a background thread. Idempotent: an association
     * that is already gone only produces a log line.
     */
    fun cancelAssociation(associationId: Int) = synchronized(PAIRING_LOCK) {
        val manager = manager ?: return@synchronized
        if (associations().none { it.id == associationId }) {
            Log.i(TAG, "Companion: association $associationId is already gone")
        } else {
            remove(manager, associationId)
        }
        val cleanup = PairingCleanup.decide(
            associationId = associationId,
            // Asked again instead of trusting the return value of remove(): only the system can say whether
            // the association is really gone.
            remainingIds = associations().map { it.id },
            storedId = store.confirmedAssociationId(),
            pendingId = store.pendingAssociationId(),
            serviceRunning = RemoteStatus.state.value.serviceRunning,
            confirmingId = RemoteStatus.state.value.pendingConfirmation?.associationId,
        )
        if (cleanup.clearPending) {
            store.clearPending(associationId)
        } else if (store.pendingAssociationId() == associationId) {
            Log.w(TAG, "Companion: association $associationId is still there, keeping its pending marker")
        }
        if (cleanup.clearConfirmed) store.clearConfirmed()
        if (cleanup.stopService) {
            Log.i(TAG, "Companion: stopping the connection service, no confirmed remote is left")
            RemoteConnectionService.stop(appContext)
        }
    }

    /**
     * Brings the associations and the confirmed one in line ([PairingReconciler]) and returns the association
     * that applies from now on, or `null` if none does.
     *
     * Called on every app start and resume instead of blindly observing everything: it adopts the single
     * association of an install that was set up with an earlier version (silently and only once, by decision
     * of the project owner), removes leftovers nobody can tell apart any more, and drops a stored ID whose
     * association was removed in the system settings.
     *
     * A confirmation that is running is not a reason to skip this: the association being confirmed is passed
     * in and left alone ([PairingReconciler.decide]), while leftovers of earlier attempts are still cleaned
     * up. Writes [ConfirmedRemoteStore] and therefore belongs on a background thread.
     */
    fun reconcile(): Int? = synchronized(PAIRING_LOCK) {
        val manager = manager ?: return@synchronized null
        healUnwrittenConfirmation()
        val confirmingId = RemoteStatus.state.value.pendingConfirmation?.associationId
        val associationIds = associations().map { it.id }
        val pendingId = store.pendingAssociationId()
        val storedId = store.confirmedAssociationId()
        val decision = PairingReconciler.decide(
            storedId = storedId,
            pendingId = pendingId,
            associationIds = associationIds,
            legacyMigrationDone = store.isLegacyMigrationDone(),
            confirmingId = confirmingId,
        )
        Log.i(TAG, "Companion: reconcile $associationIds (confirming $confirmingId) -> $decision")
        decision.removeIds.forEach { id -> remove(manager, id) }
        // Only what is really gone is forgotten, and a marker that belongs to a kept association goes as well:
        // the same rule as after a single removal, see [PairingCleanup.afterReconcile].
        val cleanup = PairingCleanup.afterReconcile(
            pendingId = pendingId.takeIf { decision.clearPending },
            storedId = storedId.takeIf { decision.clearStored },
            keptId = decision.keepId,
            remainingIds = associations().map { it.id },
        )
        if (cleanup.clearPending && pendingId != null) store.clearPending(pendingId)
        if (cleanup.clearConfirmed) store.clearConfirmed()
        decision.adoptId?.let { id ->
            Log.i(TAG, "Companion: adopting the single existing association $id as confirmed (one-time)")
            store.confirm(id)
        }
        if (decision.markMigrationDone) store.markLegacyMigrationDone()
        // Not while a confirmation runs: that is the one case in which the service has work to do without a
        // confirmed remote — it is holding the connection the press has to arrive on.
        if (decision.keepId == null && confirmingId == null && RemoteStatus.state.value.serviceRunning) {
            Log.i(TAG, "Companion: stopping the connection service, no confirmed remote is left")
            RemoteConnectionService.stop(appContext)
            // The outcome of the last confirmation stays: it is the only explanation the user gets for a
            // pairing that was just taken back, exactly like the last command after a service stop.
            RemoteStatus.reset(keepConfirmationOutcome = true)
        }
        decision.keepId
    }

    /**
     * Writes a confirmation that [RemoteStatus] knows about but the disk does not.
     *
     * The press is reported in the state the moment it arrives, while persisting it happens right after on
     * another thread — and the service can be destroyed in between. Without this, the disk would still show a
     * pending marker and the reconcile below would remove the association the user has just confirmed.
     */
    private fun healUnwrittenConfirmation() {
        val state = RemoteStatus.state.value
        val markedId = PairingCleanup.confirmationToHeal(
            lastConfirmation = state.lastConfirmation,
            lastConfirmedAssociationId = state.lastConfirmedAssociationId,
            pendingMarkerId = store.pendingAssociationId(),
            associationIds = associations().map { it.id },
        ) ?: return
        Log.w(TAG, "Companion: association $markedId was confirmed but not written yet, writing it now")
        store.confirm(markedId)
    }

    /**
     * "Remote vergessen": stops the presence observation, removes every association and the stored
     * confirmation, stops [RemoteConnectionService] and clears [RemoteStatus]. After that [isAssociated] is
     * `false` and the system no longer starts the service.
     *
     * Stopping the service belongs here and not in the caller: without it the foreground service would keep
     * its GATT connection and its notification for a remote that is no longer set up, and that must hold for
     * every caller, not only for the current one in `ui.MainActivity`. It happens after the disassociation, so
     * a presence event arriving in between cannot restart the service. `EVENT_ASSOCIATION_REMOVED` would stop
     * it too, but only if the system delivers it — the invariant "no association, no service" must not depend
     * on that.
     *
     * Writes [ConfirmedRemoteStore] and therefore belongs on a background thread.
     *
     * @return number of removed associations
     */
    fun forget(): Int = synchronized(PAIRING_LOCK) {
        val manager = manager ?: return@synchronized 0
        val removed = associations().count { remove(manager, it.id) }
        // Only once nothing is left: clearing the store while an association survived a failed disassociate
        // would leave an unconfirmed association behind that nothing points to any more, and nothing would
        // ever take it back.
        val left = associations().size
        if (left == 0) {
            store.clear()
        } else {
            Log.w(TAG, "Companion: $left association(s) left after forgetting, keeping the stored pairing")
        }
        if (removed > 0) {
            Log.i(TAG, "Companion: stopping the connection service after forgetting the remote")
            RemoteConnectionService.stop(appContext)
            // The last command survives RemoteStatus.onServiceStopped() on purpose, but not this: it belongs
            // to a remote that is no longer set up. Order is safe — a later onServiceStopped() only copies the
            // (now empty) last command.
            RemoteStatus.reset()
        }
        removed
    }

    /**
     * The [BluetoothDevice] of the association with [associationId], or of the confirmed association if
     * [associationId] is `null`.
     *
     * Built from the stored MAC address with `getRemoteLeDevice(mac, ADDRESS_TYPE_PUBLIC)` — the remote uses a
     * fixed public address and needs no bonding and no scan (docs/architektur.md §4, verified 11.09.2026).
     * `null` if there is no such association, it has no MAC address, or there is no Bluetooth adapter.
     *
     * The fallback is the confirmed association and no longer "the first one": with a list in the dialog there
     * can be an association the user never confirmed, and connecting to it would be connecting to a stranger's
     * remote. An [associationId] that is passed in is used as it is — during a confirmation that is exactly the
     * not-yet-confirmed association the press has to arrive on.
     */
    fun remoteDevice(associationId: Int? = null): BluetoothDevice? {
        val id = associationId ?: confirmedAssociationId()
        if (id == null) {
            Log.w(TAG, "Companion: no confirmed association")
            return null
        }
        val info = associations().firstOrNull { it.id == id }
        if (info == null) {
            Log.w(TAG, "Companion: no association $id")
            return null
        }
        // checkBluetoothAddress/getRemoteLeDevice only accept upper case; MacAddress.toString() is lower case.
        val address = info.deviceMacAddress?.toString()?.uppercase(Locale.ROOT)
        if (address == null) {
            Log.w(TAG, "Companion: association ${info.id} has no MAC address")
            return null
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Log.e(TAG, "Companion: no Bluetooth adapter")
            return null
        }
        return try {
            adapter.getRemoteLeDevice(address, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Companion: getRemoteLeDevice refused '${BleLog.mac(address)}'", e)
            null
        }
    }

    /** Stops the observation and disassociates; `true` if the association is gone afterwards. */
    private fun remove(manager: CompanionDeviceManager, associationId: Int): Boolean {
        try {
            manager.stopObservingDevicePresence(request(associationId))
        } catch (e: RuntimeException) {
            // Disassociating is what matters; a failed stop must not prevent it.
            Log.w(TAG, "Companion: stopObservingDevicePresence($associationId) failed", e)
        }
        return try {
            manager.disassociate(associationId)
            Log.i(TAG, "Companion: association $associationId removed")
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Companion: disassociate($associationId) failed", e)
            false
        }
    }

    private fun request(associationId: Int): ObservingDevicePresenceRequest =
        ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build()

    companion object {
        private const val TAG = CratoniBleManager.TAG

        /**
         * Serialises every change to the pairing (associations plus [ConfirmedRemoteStore]) across all threads
         * and all instances of this class — the UI holds one, the connection service another. See the class
         * comment for why.
         */
        private val PAIRING_LOCK = Any()

        /**
         * Device name of the remote, exactly `"Remote control-"` plus the four hex digits of the last two bytes
         * of its MAC address, e.g. `"Remote control-3D2C"`; exact case (docs/architektur.md §3, observed
         * format). Anchored and narrow on purpose: the dialog now shows a list the user picks from, and
         * everything that cannot be a CRATONI remote only makes that choice harder.
         */
        const val NAME_PATTERN = "^Remote control-[0-9A-Fa-f]{4}$"

        /**
         * Whether [errorCode] of [associate]'s failure callback means "the user did not want it" rather than
         * "it did not work". A rejected or cancelled dialog is not a failure and must not be reported as one.
         */
        fun isUserCancellation(errorCode: Int?): Boolean =
            errorCode == CompanionDeviceManager.RESULT_USER_REJECTED ||
                errorCode == CompanionDeviceManager.RESULT_CANCELED
    }
}
