package de.werklade.cratoniremote.companion

import de.werklade.cratoniremote.data.ConfirmationOutcome

/**
 * What to clean up after trying to remove associations — one ([decide], [CompanionSetup.cancelAssociation]) or
 * the ones a reconcile decided on ([afterReconcile], [CompanionSetup.reconcile]). Pure Kotlin, so the order
 * "only forget what is really gone" is unit tested in both cases instead of written twice.
 *
 * The point of the order: `disassociate` can fail (the system refuses it, the association is unknown). Clearing
 * the stored ID or the pending marker then would leave an association behind that nothing points to any more —
 * and an association without a marker is exactly what the reconciler could once have adopted silently. So
 * nothing is forgotten before the association has actually disappeared; if it has not, the markers stay and
 * the next app start tries again.
 */
internal object PairingCleanup {

    /**
     * @param clearPending delete the pending marker
     * @param clearConfirmed delete the stored confirmed association
     * @param stopService stop [de.werklade.cratoniremote.service.RemoteConnectionService]: it has no confirmed
     *   remote left to hold a connection for
     */
    data class Decision(
        val clearPending: Boolean = false,
        val clearConfirmed: Boolean = false,
        val stopService: Boolean = false,
    )

    /**
     * @param associationId the association that was supposed to be removed
     * @param remainingIds the associations that still exist, read **after** the removal attempt
     * @param storedId confirmed association on disk, `null` if none
     * @param pendingId association that was waiting for its confirmation press on disk, `null` if none
     * @param serviceRunning whether the connection service is up
     * @param confirmingId association whose confirmation press is being waited for right now, `null` if none.
     *   If that is another association than [associationId], the service is holding the connection the press
     *   has to arrive on and must not be stopped — the case when a leftover association is taken back while a
     *   new setup is already running.
     */
    fun decide(
        associationId: Int,
        remainingIds: List<Int>,
        storedId: Int?,
        pendingId: Int?,
        serviceRunning: Boolean,
        confirmingId: Int? = null,
    ): Decision {
        val gone = associationId !in remainingIds
        // A confirmed remote that is still there keeps the service alive; the removed one never does.
        val confirmedLeft = storedId != null && storedId != associationId && storedId in remainingIds
        val servesAnotherConfirmation = confirmingId != null && confirmingId != associationId
        return Decision(
            clearPending = gone && pendingId == associationId,
            clearConfirmed = gone && storedId == associationId,
            stopService = serviceRunning && !confirmedLeft && !servesAnotherConfirmation,
        )
    }

    /**
     * The same question after a reconcile, where several associations were removed at once and one may have
     * been kept.
     *
     * Two reasons make a pending marker stale: the association it points to is gone, or it is the one that
     * stays — then the press it was waiting for did arrive and only the marker was left behind. It stays
     * exactly when its association is still there and is not the one that applies, because that is the case in
     * which a removal failed and the next app start has to try again.
     *
     * @param keptId the association that applies from now on ([PairingReconciler.Decision.keepId])
     * @param remainingIds the associations that still exist, read **after** the removals
     */
    fun afterReconcile(
        pendingId: Int?,
        storedId: Int?,
        keptId: Int?,
        remainingIds: List<Int>,
    ): Decision = Decision(
        clearPending = pendingId != null && (pendingId !in remainingIds || pendingId == keptId),
        clearConfirmed = storedId != null && storedId !in remainingIds,
    )

    /**
     * The association whose confirmation has to be written to disk after the fact, or `null` if there is none.
     *
     * The case: the press is reported in [de.werklade.cratoniremote.data.RemoteStatus] the moment it arrives,
     * and persisted right after on another thread — which can be cut off by the service being destroyed. The
     * disk then still shows a pending marker, and a reconcile would remove the very association the user has
     * just confirmed.
     *
     * All four conditions are needed, and the third one is the one that is easy to miss: a `CONFIRMED` outcome
     * outlives the service and the state cleanup, so without comparing the IDs it could be applied to the
     * marker of a completely different association — one the user has just chosen in the dialog and not
     * confirmed at all.
     *
     * @param lastConfirmation outcome of the last confirmation
     * @param lastConfirmedAssociationId the association that outcome belongs to
     * @param pendingMarkerId the pending marker on disk
     * @param associationIds the associations that exist
     */
    fun confirmationToHeal(
        lastConfirmation: ConfirmationOutcome?,
        lastConfirmedAssociationId: Int?,
        pendingMarkerId: Int?,
        associationIds: List<Int>,
    ): Int? {
        if (lastConfirmation != ConfirmationOutcome.CONFIRMED) return null
        if (pendingMarkerId == null || pendingMarkerId != lastConfirmedAssociationId) return null
        return pendingMarkerId.takeIf { it in associationIds }
    }
}
