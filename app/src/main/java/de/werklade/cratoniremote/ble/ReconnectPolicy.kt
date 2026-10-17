package de.werklade.cratoniremote.ble

/**
 * Bounded reconnect with growing delays for [CratoniBleManager]. Pure Kotlin.
 *
 * Defaults and rationale:
 * - 3 attempts, waiting 2 s, 5 s, 10 s before them. The first delay gives the Bluetooth stack time to
 *   release the closed GATT client (an immediate reconnect after status 133 is commonly reported to fail
 *   again — assumption, not measured). Growing delays catch brief drop-outs quickly without hammering the
 *   radio. Worst case until [BleConnectionState.Failed]: 17 s waiting plus the step timeouts of up to 3 attempts
 *   (see [CratoniBleManager.CONNECT_TIMEOUT_MS] and following).
 *   From step 5 on, a new presence event of the remote triggers a fresh [CratoniBleManager.connect].
 * - The attempt counter only resets if the lost connection had been [BleConnectionState.Connected] for at least
 *   [stableConnectionMs] (30 s). A remote that drops right after connecting therefore cannot cause an endless loop.
 *
 * @param delaysMs delay before attempt 1, 2, …; its size is [maxAttempts]. Empty disables reconnecting.
 */
class ReconnectPolicy(
    delaysMs: List<Long> = DEFAULT_DELAYS_MS,
    val stableConnectionMs: Long = DEFAULT_STABLE_CONNECTION_MS,
) {

    private val delays: List<Long> = delaysMs.toList()

    init {
        require(delays.all { it >= 0 }) { "delays must not be negative: $delays" }
        require(stableConnectionMs >= 0) { "stableConnectionMs must not be negative: $stableConnectionMs" }
    }

    val maxAttempts: Int get() = delays.size

    /** Delay before the 1-based [attempt], or `null` if [attempt] is outside `1..maxAttempts`. */
    fun delayBeforeAttempt(attempt: Int): Long? = if (attempt >= 1) delays.getOrNull(attempt - 1) else null

    /** `false` for failures a reconnect cannot fix (Bluetooth off/missing, permission, unsuitable device). */
    fun isRetryable(reason: FailureReason): Boolean = when (reason) {
        FailureReason.BluetoothAdapterMissing,
        FailureReason.BluetoothDisabled,
        FailureReason.MissingPermission,
        is FailureReason.DeviceUnsuitable -> false
        is FailureReason.GattError,
        is FailureReason.ConnectionLost,
        is FailureReason.OperationNotStarted,
        is FailureReason.Timeout -> true
    }

    /**
     * Decides what happens after [reason].
     *
     * @param attemptsUsed reconnect attempts already started since the last reset, `>= 0`
     * @param connectedForMs how long the failed connection had been [BleConnectionState.Connected];
     *   `null` if it never got there
     */
    fun decide(reason: FailureReason, attemptsUsed: Int, connectedForMs: Long?): ReconnectDecision {
        require(attemptsUsed >= 0) { "attemptsUsed must not be negative: $attemptsUsed" }
        if (!isRetryable(reason)) return ReconnectDecision.GiveUp
        val stable = connectedForMs != null && connectedForMs >= stableConnectionMs
        val attempt = (if (stable) 0 else attemptsUsed) + 1
        val delay = delayBeforeAttempt(attempt) ?: return ReconnectDecision.GiveUp
        return ReconnectDecision.Retry(attempt, delay)
    }

    companion object {
        val DEFAULT_DELAYS_MS: List<Long> = listOf(2_000L, 5_000L, 10_000L)
        const val DEFAULT_STABLE_CONNECTION_MS = 30_000L
    }
}

/** Result of [ReconnectPolicy.decide]. */
sealed interface ReconnectDecision {

    /** Start the 1-based [attempt] after [delayMs]. */
    data class Retry(val attempt: Int, val delayMs: Long) : ReconnectDecision

    /** Not retryable or attempts exhausted. */
    data object GiveUp : ReconnectDecision
}
