package de.werklade.cratoniremote.helmet

/**
 * Sink for the frames of [HelmetFrames]; implemented by [HelmetSppClient] and by test doubles, so everything
 * above it (`de.werklade.cratoniremote.action.HelmetLightExecutor`) stays free of Android.
 */
interface HelmetFrameSender {

    /**
     * Hands [frame] over for sending.
     *
     * @return `true` if the frame was accepted for a standing connection, `false` if there is no connection —
     *   then nothing was sent and the caller must not change its idea of the helmet's lights. `true` is not a
     *   confirmation that the helmet received it: the write happens on another thread and the helmet does not
     *   acknowledge light frames at all (docs/architektur.md §13).
     */
    fun send(frame: ByteArray): Boolean
}
