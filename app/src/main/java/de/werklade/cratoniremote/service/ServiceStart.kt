package de.werklade.cratoniremote.service

/**
 * Whether the host of the screen has to start [RemoteConnectionService] itself after a reconcile. Pure Kotlin,
 * so the rule is unit tested.
 *
 * Normally the service is started by the system's presence events only. Two cases leave a set up remote
 * without one, both seen on the device (17.10.2026) or trivially reachable:
 *  * the pairing was just adopted or re-established, and the remote is already in range. The system reports a
 *    device as present once; a remote that is switched on and was never reported as gone produces no new event
 *    (docs/architektur.md §11), so nothing would ever start the service.
 *  * the process was killed ("force-stop", a crash) while the remote stayed where it was — same situation.
 *
 * The app cannot ask whether the remote is in range (that would need a scan and `BLUETOOTH_SCAN`, which this
 * app deliberately does not have), so the start is an attempt: if the remote is away, the connection fails and
 * the service stops itself ([FailureStopPolicy]), which ends in the normal "Nicht in Reichweite" state.
 *
 * Not while Bluetooth is off: the service would not fail then but wait for the adapter, and that waiting is
 * deliberately unbounded ([RemoteConnectionService]) — opening the app with Bluetooth switched off would leave
 * a permanent notification behind. The caller tries again when the adapter comes back.
 *
 * [alreadyTriedFor] keeps that attempt to one per applicable association instead of one per `ON_RESUME`: the
 * caller remembers the association it last tried, and a value that is still the same says "already tried".
 *
 * @param applicableAssociationId the association that applies after the reconcile
 *   (`companion.CompanionSetup.reconcile`), `null` if none does
 * @param serviceRunning whether the connection service is already up
 * @param confirmationRunning whether a confirmation press is being waited for — then the service was started
 *   for exactly that and must not be started for anything else
 * @param bluetoothEnabled whether the adapter is on
 * @param alreadyTriedFor association the caller has already started the service for, `null` if none
 */
internal fun shouldStartConnectionService(
    applicableAssociationId: Int?,
    serviceRunning: Boolean,
    confirmationRunning: Boolean,
    bluetoothEnabled: Boolean,
    alreadyTriedFor: Int?,
): Boolean = applicableAssociationId != null &&
    !serviceRunning &&
    !confirmationRunning &&
    bluetoothEnabled &&
    applicableAssociationId != alreadyTriedFor
