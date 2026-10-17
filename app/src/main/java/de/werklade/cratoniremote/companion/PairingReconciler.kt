package de.werklade.cratoniremote.companion

/**
 * Brings the confirmed association, the pending marker ([ConfirmedRemoteStore]) and the associations the system
 * knows (`CompanionDeviceManager.getMyAssociations()`) back in line. Pure Kotlin, so the rules are unit tested.
 *
 * Why this is needed: since the association dialog offers a list (several identical `Remote control-XXXX` on a
 * group ride), an association can exist that the user never confirmed with a button press — the process can die
 * between the dialog and the confirmation, or the user can remove an association in the system settings. Exactly
 * one association may count as "the remote", and only a confirmed one may be connected to.
 *
 * The rules, in the order [decide] applies them:
 *  * the association that is being confirmed right now is never touched: it is not stored yet, and removing it
 *    would cancel the setup the user is in the middle of
 *  * the stored association still exists → it stays, every other association is removed
 *  * nothing stored and exactly one association **without a pending marker on it**, and the one-time
 *    migration has not run yet → it is adopted silently (decision of the project owner: an install that was
 *    set up with an earlier version must not ask again — there is no marker in that case, because the version
 *    that paired it knew none)
 *  * an association the pending marker points to is never adopted: the press it was waiting for never arrived,
 *    so it may be anybody's remote and is removed
 *  * nothing stored and more than one association → none of them can be told apart, all are removed; the user
 *    sets up again and confirms with a button press
 *  * the stored association is gone → the stored ID is dropped, then the rules above apply
 *
 * The pending marker never survives: it only exists while a press is being waited for, and that waiting lives
 * in memory ([de.werklade.cratoniremote.data.RemoteState.pendingConfirmation]). A marker that is still on disk
 * when this runs therefore belongs to a confirmation nobody is waiting for any more.
 *
 * The adoption is a **one-time migration**, not a rule: it runs on the first reconcile of an install and is
 * closed afterwards ([Decision.markMigrationDone], `ConfirmedRemoteStore.isLegacyMigrationDone`). Adoption is
 * the only direction in which a mistake here hands the user a remote nobody confirmed — a missing or
 * unwritable pending marker, a cancelled coroutine, a failed `disassociate` would all end in it. Closing the
 * window after the first run means every one of those cases ends in "set up again" instead.
 */
internal object PairingReconciler {

    /**
     * What the caller has to do. Invariants: [adoptId] is either `null` or equal to [keepId]; [removeIds] never
     * contains [keepId]; [clearStored] and [adoptId] are never set at the same time (writing the adopted ID
     * replaces the stored one anyway).
     *
     * @param keepId the association that applies from now on, `null` if none does
     * @param adoptId association that has to be written to [ConfirmedRemoteStore] because it was not stored yet
     * @param removeIds associations to disassociate, in the order they were passed in
     * @param clearStored whether the stored ID has to be deleted: it belongs to an association that no longer
     *   exists and nothing takes its place
     * @param clearPending whether the pending marker has to be deleted
     * @param markMigrationDone whether the one-time adoption window has to be closed now; set on the first
     *   reconcile of an install, whether or not something was adopted
     */
    data class Decision(
        val keepId: Int? = null,
        val adoptId: Int? = null,
        val removeIds: List<Int> = emptyList(),
        val clearStored: Boolean = false,
        val clearPending: Boolean = false,
        val markMigrationDone: Boolean = false,
    )

    /**
     * The decision for [storedId] (confirmed association, `null` if none is stored), [pendingId] (association
     * that was waiting for its confirmation press, `null` if none was), [associationIds] (all associations of
     * this app), [legacyMigrationDone] (whether the one-time adoption window is already closed) and
     * [confirmingId] (association whose confirmation press is being waited for **right now**, `null` if none).
     *
     * [confirmingId] is the association the user has just chosen: it is deliberately left alone instead of
     * handled, because every other outcome would end the setup that is in progress. Its marker stays too, and
     * nothing is adopted while it runs — the user is in the middle of saying which remote is theirs.
     */
    fun decide(
        storedId: Int?,
        pendingId: Int?,
        associationIds: List<Int>,
        legacyMigrationDone: Boolean,
        confirmingId: Int? = null,
    ): Decision {
        val stored = storedId?.takeIf { it in associationIds }
        // The stored ID no longer exists: it must not stay in the store, where a later association could inherit
        // its number and count as confirmed without anybody ever pressing a button.
        val orphaned = storedId != null && stored == null
        // Everything that may be removed: neither the confirmed association nor the one being confirmed.
        val candidates = associationIds.filter { it != confirmingId && it != stored }
        // A marker that is still here is stale — unless it belongs to the confirmation that is running.
        val clearPending = pendingId != null && pendingId != confirmingId
        // Whatever this run decides, the one-time window is closed afterwards.
        val markMigrationDone = !legacyMigrationDone
        return when {
            stored != null -> Decision(
                keepId = stored,
                removeIds = candidates,
                clearPending = clearPending,
                markMigrationDone = markMigrationDone,
            )
            // The single association is only the user's remote if it was not the one still waiting for a press
            // and the migration window is still open — otherwise it was never confirmed by anybody. Never
            // while a confirmation runs: that one is about to answer the question properly.
            confirmingId == null &&
                !legacyMigrationDone &&
                candidates.size == 1 &&
                candidates.first() != pendingId -> Decision(
                keepId = candidates.first(),
                adoptId = candidates.first(),
                clearPending = clearPending,
                markMigrationDone = true,
            )
            else -> Decision(
                removeIds = candidates,
                clearStored = orphaned,
                clearPending = clearPending,
                markMigrationDone = markMigrationDone,
            )
        }
    }
}
