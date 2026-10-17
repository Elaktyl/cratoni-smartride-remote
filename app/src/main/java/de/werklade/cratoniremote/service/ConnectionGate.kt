package de.werklade.cratoniremote.service

import de.werklade.cratoniremote.data.RemoteState

/**
 * Whether [RemoteConnectionService] may connect to the association [requestedId]. Pure Kotlin, because this is
 * the lock that keeps the app off somebody else's remote — it is worth a unit test of its own.
 *
 * The rules, in order:
 *  * while a confirmation is running, only the association that is being confirmed. That connection exists so
 *    the press can arrive on it; any other association at that moment is a leftover or a stranger's.
 *  * without a confirmed remote, nothing. An association nobody confirmed must never be connected to, which is
 *    exactly the case a presence event for a left-over association produces.
 *  * [RemoteConnectionService.NO_ASSOCIATION_ID] means "the one the app knows", which is the confirmed one.
 *  * otherwise only the confirmed association itself.
 *
 * @param state current process-wide state, for [RemoteState.pendingConfirmation]
 * @param confirmedId confirmed association that still exists (`companion.CompanionSetup.confirmedAssociationId`)
 * @param requestedId association of the presence event or of the start intent
 */
internal fun connectAllowed(state: RemoteState, confirmedId: Int?, requestedId: Int): Boolean {
    val pendingId = state.pendingConfirmation?.associationId
    return when {
        pendingId != null -> requestedId == pendingId
        confirmedId == null -> false
        requestedId == RemoteConnectionService.NO_ASSOCIATION_ID -> true
        else -> requestedId == confirmedId
    }
}

/** What [RemoteConnectionService] does with a start for an association; see [connectDecision]. */
internal enum class ConnectVerdict {

    /** Connect to it. */
    CONNECT,

    /**
     * Not yet: nothing is confirmed, but the one-time migration of a pairing from a version without the
     * confirmation step has not run. The decision has to be made first
     * (`companion.PairingReconciler` through `companion.CompanionSetup.reconcile`, the same rule path the UI
     * uses), then this is asked again.
     */
    RESOLVE_MIGRATION,

    /** Do not connect, and stop: this association is not, and will not become, the user's remote. */
    REFUSE,
}

/**
 * What to do with a start for [requestedId]. Pure Kotlin for the same reason as [connectAllowed]: this is the
 * line between the user's remote and a stranger's.
 *
 * [ConnectVerdict.RESOLVE_MIGRATION] exists because of a case the device test showed (17.10.2026): after
 * `install -r` on an installation that was paired with an earlier version, the system reported the remote as
 * present before the user had opened the app once. Nothing was confirmed yet — the adoption used to happen in
 * the Activity only — so the service refused and stopped, and the remote stayed dead until it was switched off
 * and on again. The service now resolves that migration itself.
 *
 * It is deliberately narrow: only when nothing is confirmed, no confirmation is running and the one-time
 * window is still open. Once the window is closed, "nothing confirmed" means "nobody ever confirmed this
 * association", and that is the case the whole confirmation step exists for — it stays refused.
 *
 * @param legacyMigrationDone `companion.ConfirmedRemoteStore.isLegacyMigrationDone`
 */
internal fun connectDecision(
    state: RemoteState,
    confirmedId: Int?,
    requestedId: Int,
    legacyMigrationDone: Boolean,
): ConnectVerdict = when {
    connectAllowed(state, confirmedId, requestedId) -> ConnectVerdict.CONNECT
    state.pendingConfirmation == null && confirmedId == null && !legacyMigrationDone ->
        ConnectVerdict.RESOLVE_MIGRATION
    else -> ConnectVerdict.REFUSE
}
