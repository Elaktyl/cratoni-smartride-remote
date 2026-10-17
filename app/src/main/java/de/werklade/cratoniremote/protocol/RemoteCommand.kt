package de.werklade.cratoniremote.protocol

/**
 * Commands of the CRATONI SMARTRIDE 1.2 remote.
 *
 * [UNKNOWN] marks a formally valid packet whose byte sequence is not mapped.
 */
enum class RemoteCommand {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    VOLUME_UP,
    VOLUME_DOWN,
    CALL,
    WALKIE_TALKIE,
    TURN_LEFT,
    TURN_RIGHT,
    REAR_LIGHT,
    UNKNOWN,
}
