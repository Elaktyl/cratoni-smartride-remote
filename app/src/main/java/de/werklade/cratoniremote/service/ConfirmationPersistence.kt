package de.werklade.cratoniremote.service

import de.werklade.cratoniremote.companion.ConfirmedRemoteWriter
import de.werklade.cratoniremote.data.RemoteState

/**
 * Persists the confirmation of the remote that [state] was waiting for, and returns its association ID;
 * `null` if nothing was being confirmed, in which case nothing is written.
 *
 * The service does this and no longer the UI (decision of the project owner, 16.10.2026): only the service
 * knows for certain that the press arrived — it owns the GATT connection it arrived on — and it keeps running
 * when the Activity is gone. Done in the UI, a confirmation would be lost whenever the user left the app
 * between the press and the write, and the association would be taken back although the user had confirmed it.
 *
 * Pure apart from [writer], which is why the rule is unit tested with a fake. [writer] blocks on a file, so
 * callers run this on a background thread; writing the same association twice is harmless
 * ([ConfirmedRemoteWriter.confirm]), so a repeated call cannot do damage either.
 */
internal fun persistConfirmation(state: RemoteState, writer: ConfirmedRemoteWriter): Int? {
    val associationId = state.pendingConfirmation?.associationId ?: return null
    writer.confirm(associationId)
    return associationId
}
