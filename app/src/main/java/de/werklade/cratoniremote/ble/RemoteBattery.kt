package de.werklade.cratoniremote.ble

/**
 * One battery measurement of the remote, as published by [CratoniBleManager.battery].
 *
 * @param percent value reported by the remote, unchanged and never converted. It comes from byte 0 of the FFF4
 *   status frame ([de.werklade.cratoniremote.protocol.RemoteStatusFrame], where the open question about its exact
 *   meaning is documented) or, if that characteristic ever sends, from Battery Level 0x2A19.
 * @param millivolts cell voltage of the same measurement, `null` when the value came from Battery Level 0x2A19 —
 *   that characteristic carries a percentage only.
 * @param atEpochMs wall clock time of the measurement ([System.currentTimeMillis]); display only, never used for
 *   measuring durations.
 */
data class RemoteBattery(val percent: Int, val millivolts: Int?, val atEpochMs: Long)
