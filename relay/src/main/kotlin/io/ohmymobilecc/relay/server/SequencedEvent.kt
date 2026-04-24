package io.ohmymobilecc.relay.server

import io.ohmymobilecc.core.protocol.WireMessage

/**
 * A [WireMessage] tagged with a monotonically-increasing sequence number
 * and the wall-clock timestamp at which it was appended to the [EventLog].
 */
public data class SequencedEvent(
    val seq: Long,
    val timestampMs: Long,
    val message: WireMessage,
)
