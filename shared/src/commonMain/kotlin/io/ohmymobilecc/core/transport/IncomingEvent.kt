package io.ohmymobilecc.core.transport

import io.ohmymobilecc.core.protocol.WireMessage

/**
 * High-level events emitted by [ReconnectingTransportPort.events].
 *
 * These decouple downstream consumers from raw [WireMessage] framing
 * and transport-level concerns (seq tracking, replay boundaries).
 */
public sealed class IncomingEvent {
    /** A sequenced application message from the relay. */
    public data class Message(
        val seq: Long,
        val message: WireMessage,
    ) : IncomingEvent()

    /** The relay finished replaying missed events after a reconnection. */
    public data class ReplayComplete(
        val count: Int,
        val hasGap: Boolean,
    ) : IncomingEvent()

    /** A new live connection has been established (first connect or reconnect). */
    public object ConnectionEstablished : IncomingEvent() {
        override fun toString(): String = "ConnectionEstablished"
    }

    /** The underlying connection was lost. [reason] is a human-readable hint. */
    public data class ConnectionLost(
        val reason: String,
    ) : IncomingEvent()
}
