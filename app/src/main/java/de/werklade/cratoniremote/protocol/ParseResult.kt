package de.werklade.cratoniremote.protocol

/**
 * Result of [CratoniProtocolParser.parse].
 *
 * [rawHex] is the received packet as uppercase hex bytes separated by single spaces
 * (e.g. `"0F 55 03 68"`, empty string for an empty packet), intended for display and logging.
 * The packet array itself is not retained, so equality is value-based.
 */
sealed interface ParseResult {
    val rawHex: String

    /** Formally valid packet (length, header, checksum). Unmapped byte sequences yield [RemoteCommand.UNKNOWN]. */
    data class Command(val command: RemoteCommand, override val rawHex: String) : ParseResult

    /** Rejected packet. Must never trigger an action. */
    data class Invalid(val reason: InvalidReason, override val rawHex: String) : ParseResult
}

enum class InvalidReason {
    EMPTY,
    TOO_SHORT,
    UNEXPECTED_LENGTH,
    INVALID_HEADER,
    INVALID_CHECKSUM,
}
