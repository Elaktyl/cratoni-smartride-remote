package de.werklade.cratoniremote.companion

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import de.werklade.cratoniremote.ble.CratoniBleManager

/**
 * Persists the confirmation of a chosen remote. The one thing the service needs from
 * [ConfirmedRemoteStore], as an interface so the rule around it can be unit tested with a fake
 * (see `de.werklade.cratoniremote.service.persistConfirmation`).
 */
internal interface ConfirmedRemoteWriter {

    /**
     * Remembers [associationId] as the confirmed remote and drops a pending marker for it. Blocks until both
     * are written; writing the same ID again must stay harmless.
     */
    fun confirm(associationId: Int)
}

/**
 * The two things the app persists itself: which association the user confirmed with a press on the remote, and
 * which one is currently waiting for that press.
 *
 * Until now `CompanionDeviceManager.getMyAssociations()` was the whole truth ("is a remote set up"). With a
 * list in the association dialog that is no longer enough: an association can exist without ever having been
 * confirmed, and the system cannot tell the two apart. Only association IDs are stored — no MAC address, no
 * name, nothing that identifies the user; the IDs are meaningless outside this app's associations.
 *
 * The pending marker is what makes the confirmation survive a process death. It is written **before** the
 * connection for the confirmation is started, so an association that was chosen but never confirmed can be
 * recognised as exactly that on the next app start and is removed instead of silently adopted
 * ([PairingReconciler]) — otherwise a remote picked out of a list by mistake, maybe a fellow rider's, would
 * become "the remote" just because the app was killed at the wrong moment.
 *
 * [SharedPreferences] like [de.werklade.cratoniremote.service.LowBatteryNotifier], but with
 * [SharedPreferences.Editor.commit] instead of `apply()`: both values have to survive the process being killed
 * in the next moment, which is the whole point of having them.
 *
 * Threading: [confirm], [markPending], [markLegacyMigrationDone], [clearConfirmed], [clearPending] and
 * [clear] block on the file and must therefore be called from a background thread — never from the BLE thread, which delivers the button packets, and never
 * from the main thread. [confirmedAssociationId] and [pendingAssociationId] only wait for the preferences file
 * to be loaded (which Android does on its own thread) and may be called from any thread; they are read in
 * exactly those places that have to decide right away whether a presence event belongs to the confirmed remote.
 */
class ConfirmedRemoteStore(context: Context) : ConfirmedRemoteWriter {

    private val appContext: Context = context.applicationContext

    /** Opened on first use, not while the service or the Activity is being created. */
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * The confirmed association ID, or `null` if none was confirmed yet. Says nothing about whether that
     * association still exists — [PairingReconciler] answers that.
     */
    fun confirmedAssociationId(): Int? = prefs.getInt(KEY_CONFIRMED_ASSOCIATION_ID, NONE).takeIf { it != NONE }

    /**
     * The association that was waiting for its confirmation press when this marker was written, or `null`.
     *
     * A marker that is still there on app start means the press never arrived — nobody is waiting any more,
     * because the waiting itself lives in memory ([de.werklade.cratoniremote.data.RemoteState]).
     */
    fun pendingAssociationId(): Int? = prefs.getInt(KEY_PENDING_ASSOCIATION_ID, NONE).takeIf { it != NONE }

    /**
     * Notes [associationId] as waiting for its confirmation press; blocks until it is written.
     *
     * @return whether the marker is really on disk. `false` means the safety net is missing, so the caller has
     *   to take the association back instead of waiting for a press it could not undo later.
     */
    fun markPending(associationId: Int): Boolean {
        val written = prefs.edit().putInt(KEY_PENDING_ASSOCIATION_ID, associationId).commit()
        if (written) {
            Log.i(TAG, "Pairing: association $associationId marked as waiting for its confirmation press")
        } else {
            Log.w(TAG, "Pairing: marking association $associationId as pending failed")
        }
        return written
    }

    /**
     * Reads one value just to open the preferences file. Called on a background thread while the service is
     * being created, so the first read that has to answer right away — "is this presence event the confirmed
     * remote?" on the main thread — is less likely to wait for the disk.
     *
     * A head start, not a guarantee: nobody waits for this call, so a read on the main thread can still be
     * the one that triggers the loading.
     */
    fun warmUp() {
        prefs.getInt(KEY_CONFIRMED_ASSOCIATION_ID, NONE)
    }

    /**
     * Whether the one-time adoption of a pairing from a version without the confirmation step has already
     * happened (or was established as not applicable). See [PairingReconciler] — after that, an association
     * nobody confirmed is never adopted again, whatever went wrong with the pending marker.
     */
    fun isLegacyMigrationDone(): Boolean = prefs.getBoolean(KEY_LEGACY_MIGRATION_DONE, false)

    /** Closes the one-time adoption window for good; blocks until it is written. */
    fun markLegacyMigrationDone() {
        val written = prefs.edit().putBoolean(KEY_LEGACY_MIGRATION_DONE, true).commit()
        Log.i(TAG, "Pairing: legacy adoption window closed (written: $written)")
    }

    /**
     * Remembers [associationId] as confirmed, for good, and drops the pending marker in the same write: a
     * confirmed association is not waiting for anything any more. Writing the same ID twice changes nothing,
     * which is what lets the service persist a press without having to know whether it already did.
     */
    override fun confirm(associationId: Int) {
        val written = prefs.edit()
            .putInt(KEY_CONFIRMED_ASSOCIATION_ID, associationId)
            .remove(KEY_PENDING_ASSOCIATION_ID)
            .commit()
        if (written) {
            Log.i(TAG, "Pairing: association $associationId stored as confirmed")
        } else {
            // Nothing to do about it here: the pending marker then stays, and the next app start removes the
            // association instead of trusting it — the safe direction.
            Log.w(TAG, "Pairing: storing the confirmed association $associationId failed")
        }
    }

    /** Forgets the confirmed association but keeps the pending marker; blocks until it is written. */
    fun clearConfirmed() {
        val written = prefs.edit().remove(KEY_CONFIRMED_ASSOCIATION_ID).commit()
        Log.i(TAG, "Pairing: confirmed association cleared (written: $written)")
    }

    /**
     * Forgets the pending marker, but only if it still points to [expectedId]; blocks until it is written.
     *
     * The expectation matters because the marker holds one ID: a caller that cleans up after an association it
     * just took back must not delete the marker of a confirmation that has been started in the meantime — that
     * association would then be the one nothing recognises as unconfirmed.
     */
    fun clearPending(expectedId: Int) {
        val current = pendingAssociationId()
        if (current != expectedId) {
            Log.i(TAG, "Pairing: pending marker is $current, not $expectedId, leaving it alone")
            return
        }
        val written = prefs.edit().remove(KEY_PENDING_ASSOCIATION_ID).commit()
        Log.i(TAG, "Pairing: pending confirmation of association $expectedId cleared (written: $written)")
    }

    /**
     * Forgets the confirmed association and the pending marker; blocks until it is written. The migration flag
     * deliberately stays: "Remote vergessen" is not a reason to open the silent-adoption window again.
     */
    fun clear() {
        val written = prefs.edit()
            .remove(KEY_CONFIRMED_ASSOCIATION_ID)
            .remove(KEY_PENDING_ASSOCIATION_ID)
            .commit()
        Log.i(TAG, "Pairing: confirmed association and pending marker cleared (written: $written)")
    }

    companion object {
        private const val TAG = CratoniBleManager.TAG

        /** Own file, separate from the battery warning's `remote_battery`. */
        const val PREFS_NAME = "pairing"

        /** Association ID of the confirmed remote, [NONE] if there is none. */
        const val KEY_CONFIRMED_ASSOCIATION_ID = "confirmed_association_id"

        /** Association ID that was waiting for its confirmation press, [NONE] if none was. */
        const val KEY_PENDING_ASSOCIATION_ID = "pending_association_id"

        /** Boolean, `false` until the one-time adoption of a pre-confirmation pairing was settled. */
        const val KEY_LEGACY_MIGRATION_DONE = "migrated_legacy_association"

        /** No association. `-1` is no valid association ID; the system numbers them from 1. */
        const val NONE = -1
    }
}
