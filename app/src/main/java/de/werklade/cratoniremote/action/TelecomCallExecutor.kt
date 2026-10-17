package de.werklade.cratoniremote.action

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresPermission
import de.werklade.cratoniremote.ble.CratoniBleManager

/**
 * [CallExecutor] via [TelecomManager.acceptRingingCall] and [TelecomManager.endCall], with the current call
 * state read from [AudioManager.getMode] (see [callActionForAudioMode]).
 *
 * ## Deliberately on a deprecated API
 *
 * Both calls are deprecated since API 29 (verified in the android-37.0 stubs: `api-versions.xml` says
 * `acceptRingingCall()V since=26 deprecated=29`, `endCall()Z since=28 deprecated=29`). The documented
 * replacement is an [android.telecom.InCallService], which a non-telephony app may only bind with
 * `MANAGE_ONGOING_CALLS`. That permission is granted solely through a `CompanionDeviceManager` association
 * with a device profile, and the documented profiles are WATCH and GLASSES. Taking one of them would mean
 * declaring this helmet remote to be a smartwatch, asking the user to pair again, and holding a bundle of
 * telephone, SMS, contacts and nearby permissions — for a single button. The project therefore chose the
 * deprecated route knowingly; deprecated is not removed, both methods exist and work in API 37.
 *
 * The permission actually needed here is [Manifest.permission.ANSWER_PHONE_CALLS] (dangerous, requested at
 * runtime by `MainActivity`): the stubs annotate `acceptRingingCall()` with
 * `anyOf {ANSWER_PHONE_CALLS, MODIFY_PHONE_STATE}` and `endCall()` with `ANSWER_PHONE_CALLS`;
 * `MODIFY_PHONE_STATE` is signature-only and out of reach for apps. Nothing else is requested — in
 * particular no `READ_PHONE_STATE`, which `TelecomManager.isInCall()` would have required.
 *
 * ## Known limits of this route
 *
 * - Call waiting (a call running plus a second one ringing) is reported as one audio mode, and that mode is
 *   "in call": a press then ends the running call instead of accepting the second one. Telling the two calls
 *   apart needs the per-call state of an [android.telecom.InCallService] — see above why there is none.
 * - [TelecomManager.acceptRingingCall] returns `void`. A [DispatchResult.Executed] therefore means "called,
 *   no exception", not "demonstrably connected"; whether the call really came up is only visible on the
 *   device.
 *
 * ## Behaviour
 *
 * Without the permission, without a [TelecomManager] or without a call, nothing happens: the attempt is
 * logged and `false` returned, which [ActionDispatcher] reports as [DispatchResult.Ignored]. A
 * [SecurityException] from the two calls — the permission revoked between check and call, or refused by the
 * platform — is caught and treated the same way: logged, `false`. A press of the call button is never
 * reported as a malfunction.
 *
 * Right after a successful accept, hanging up is locked for [HANG_UP_LOCK_MS] milliseconds: the audio mode
 * needs roughly 0.2–0.5 s to reach "in call", so a second press meant for the first one would otherwise end
 * the call that was just accepted. The lock only blocks hanging up; accepting stays possible at any time.
 *
 * All state is read from the BLE callback thread, hence [Volatile].
 *
 * @param context used for [Context.checkSelfPermission] and to look up the [TelecomManager]; hold the
 *   application or service context, never an Activity
 */
class TelecomCallExecutor internal constructor(
    /** `null` on a device without telephony support; then the call button stays without effect. */
    private val platformCalls: PlatformCalls?,
    private val permissionGranted: () -> Boolean,
    private val modeProvider: () -> Int,
    private val clock: () -> Long,
    private val log: (Int, String) -> Unit,
) : CallExecutor {

    /**
     * The constructor for the app. Everything the logic depends on comes from Android here: the audio mode
     * from [AudioManager], the time from [SystemClock.elapsedRealtime] (monotonic, unlike wall clock time it
     * cannot jump when the network corrects the clock).
     */
    constructor(context: Context, audioManager: AudioManager) : this(
        platformCalls = context.getSystemService(TelecomManager::class.java)
            ?.let { TelecomPlatformCalls(it) },
        permissionGranted = {
            context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) ==
                PackageManager.PERMISSION_GRANTED
        },
        modeProvider = { audioManager.mode },
        clock = SystemClock::elapsedRealtime,
        log = { priority, message -> Log.println(priority, TAG, message) },
    )

    /** When the last call was accepted, [NEVER] until the first one. See [HANG_UP_LOCK_MS]. */
    @Volatile
    private var acceptedAt: Long = NEVER

    /** Guards the one-off [logCompanionAccess] line; see there why it is worth a log entry at all. */
    @Volatile
    private var companionAccessLogged = false

    override fun answerOrHangUp(): Boolean {
        val calls = platformCalls
        if (calls == null) {
            log(Log.WARN, "Call: no TelecomManager on this device, call button without effect")
            return false
        }
        logCompanionAccess(calls)

        val mode = modeProvider()
        val action = callActionForAudioMode(mode)
        if (action == CallAction.NONE) {
            log(Log.INFO, "Call: no call in progress (audio mode $mode), nothing to do")
            return false
        }
        if (!permissionGranted()) {
            log(Log.WARN, "Call: ANSWER_PHONE_CALLS not granted, $action not performed")
            return false
        }
        return try {
            perform(calls, action)
        } catch (e: SecurityException) {
            // Permission revoked between check and call, or the platform refuses this app: not a
            // malfunction of the remote, so it ends as Ignored like every other "nothing happened".
            log(Log.WARN, "Call: $action refused by the platform: $e")
            false
        }
    }

    private fun perform(calls: PlatformCalls, action: CallAction): Boolean = when (action) {
        CallAction.ANSWER -> {
            calls.acceptRingingCall()
            acceptedAt = clock()
            log(Log.INFO, "Call: ringing call accepted")
            true
        }
        CallAction.HANG_UP -> {
            val sinceAccept = sinceAccept()
            if (sinceAccept < HANG_UP_LOCK_MS) {
                log(Log.INFO, "Call: accepted $sinceAccept ms ago, hang up ignored")
                false
            } else {
                // false e.g. for an emergency call, which the platform refuses to end, or for a
                // self-managed VoIP call this app is not privileged for.
                val ended = calls.endCall()
                log(Log.INFO, "Call: endCall() returned $ended")
                ended
            }
        }
        CallAction.NONE -> false
    }

    /** Milliseconds since the last accept, [Long.MAX_VALUE] if there was none in this process. */
    private fun sinceAccept(): Long {
        val accepted = acceptedAt
        return if (accepted == NEVER) Long.MAX_VALUE else clock() - accepted
    }

    /**
     * Logs once per executor whether this app holds `MANAGE_ONGOING_CALLS`, the permission an
     * [android.telecom.InCallService] would need. Expected: `false` — the association in `CompanionSetup`
     * carries no device profile. The line is the evidence for that in a real log, so the decision above
     * rests on an observation rather than on an assumption. [TelecomManager.hasManageOngoingCallsPermission]
     * itself needs no permission (API 31, no `@RequiresPermission` in the android-37.0 stubs).
     */
    private fun logCompanionAccess(calls: PlatformCalls) {
        if (companionAccessLogged) return
        companionAccessLogged = true
        log(Log.INFO, "Call: hasManageOngoingCallsPermission()=${calls.hasManageOngoingCallsPermission()}")
    }

    internal companion object {
        private const val TAG = CratoniBleManager.TAG

        /**
         * How long after an accept a hang up is ignored. Covers the roughly 0.2–0.5 s the audio mode needs
         * to switch to "in call" plus the time a finger stays on the button — a double press on the one key
         * the remote has must not end the call it just took.
         */
        internal const val HANG_UP_LOCK_MS = 3_000L

        /** [acceptedAt] before the first accept; never a valid [SystemClock.elapsedRealtime] value. */
        private const val NEVER = Long.MIN_VALUE
    }
}

/**
 * The two deprecated [TelecomManager] calls behind an interface, so the logic of [TelecomCallExecutor] can
 * be tested without an Android runtime — [TelecomManager] is impossible to build outside a device.
 */
internal interface PlatformCalls {
    fun acceptRingingCall()

    /** `false` if the platform refused to end the call. */
    fun endCall(): Boolean

    fun hasManageOngoingCallsPermission(): Boolean
}

/**
 * The real implementation. Every method that needs [Manifest.permission.ANSWER_PHONE_CALLS] declares it with
 * [RequiresPermission]; the guard itself sits in [TelecomCallExecutor], which is where it can be tested.
 */
private class TelecomPlatformCalls(private val telecom: TelecomManager) : PlatformCalls {

    @RequiresPermission(Manifest.permission.ANSWER_PHONE_CALLS)
    @Suppress("DEPRECATION") // deprecated since API 29 — see the KDoc of TelecomCallExecutor
    override fun acceptRingingCall() = telecom.acceptRingingCall()

    @RequiresPermission(Manifest.permission.ANSWER_PHONE_CALLS)
    @Suppress("DEPRECATION") // deprecated since API 29 — see the KDoc of TelecomCallExecutor
    override fun endCall(): Boolean = telecom.endCall()

    override fun hasManageOngoingCallsPermission(): Boolean = telecom.hasManageOngoingCallsPermission()
}

/** What the call button should do, derived from the call state. */
internal enum class CallAction { ANSWER, HANG_UP, NONE }

/**
 * The one place that decides "is it ringing or is a call running", from [AudioManager.getMode].
 *
 * The audio mode is the cheapest usable source: it needs no permission at all, while the only other public
 * option, `TelecomManager.isInCall()`, would cost `READ_PHONE_STATE` and still could not tell ringing from
 * active (its own documentation lists dialing, ringing, active and holding as one state). A public
 * `TelecomManager.isRinging()` does not exist in API 37.
 *
 * It is a heuristic, so it is kept alone in this function: swapping in an exact source of truth later — the
 * per-call [android.telecom.Call.STATE_RINGING] / [android.telecom.Call.STATE_ACTIVE] of an
 * `InCallService` — changes this function and nothing else. [volumeStreamForAudioMode] reads the same mode,
 * so the call key and the volume keys always agree on the state.
 *
 * [AudioManager.MODE_IN_COMMUNICATION] (VoIP) maps to hanging up as well, although
 * [TelecomManager.endCall] usually cannot end a self-managed call; the attempt then returns `false` and
 * stays without effect, which is still better than accepting a call that is not ringing.
 */
internal fun callActionForAudioMode(mode: Int): CallAction = when (mode) {
    AudioManager.MODE_RINGTONE -> CallAction.ANSWER
    AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> CallAction.HANG_UP
    else -> CallAction.NONE
}
