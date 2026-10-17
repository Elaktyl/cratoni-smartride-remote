package de.werklade.cratoniremote.action

/**
 * The remote's call button: accept a ringing call, end an ongoing one — one method, because the remote
 * has exactly one key for both and decides nothing itself (docs/anforderungen.md, `0F 56 03 69`).
 *
 * Which of the two happens is up to the implementation; it needs the current call state for that and is
 * the only place that knows it.
 *
 * Implementations may throw; [ActionDispatcher] reports exceptions as [DispatchResult.Failed].
 *
 * @return `true` if a call was accepted or ended, `false` if there was nothing to do — no call, or the
 *   permission for it is missing. [ActionDispatcher] turns `false` into [DispatchResult.Ignored], so a
 *   press into thin air is not reported as an error.
 */
interface CallExecutor {
    fun answerOrHangUp(): Boolean
}
