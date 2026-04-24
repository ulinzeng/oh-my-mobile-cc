package io.ohmymobilecc.core.transport

/**
 * Observable connection state exposed by [ReconnectingTransportPort].
 *
 * The state machine follows a strict progression:
 * ```
 * Disconnected → Connecting → (Replaying →) Connected → Reconnecting → Connecting → …
 * ```
 * Terminal states: [Disconnected] after a FATAL rejection or explicit [ReconnectingTransportPort.close].
 */
public sealed class ConnectionState {
    /** Initial state, or terminal state after FATAL rejection / explicit close. */
    public object Disconnected : ConnectionState() {
        override fun toString(): String = "Disconnected"
    }

    /** TCP + WS upgrade in progress; first attempt or retry. */
    public object Connecting : ConnectionState() {
        override fun toString(): String = "Connecting"
    }

    /** Handshake succeeded; relay is replaying missed events before live mode. */
    public data class Replaying(
        val progress: ReplayProgress,
    ) : ConnectionState()

    /** Live — relay is streaming real-time events. */
    public data class Connected(
        val serverTimeMs: Long,
    ) : ConnectionState()

    /** Connection lost; waiting [nextRetryMs] before attempt [attempt]. */
    public data class Reconnecting(
        val attempt: Int,
        val nextRetryMs: Long,
    ) : ConnectionState()
}

/**
 * Replay-progress metadata derived from the relay's `HelloOk` response.
 * Exposed inside [ConnectionState.Replaying] so UI can show a "catching up"
 * indicator.
 */
public data class ReplayProgress(
    /** Oldest seq the relay still holds in its ring buffer. */
    public val oldestSeq: Long,
    /** Latest seq the relay has emitted. */
    public val latestSeq: Long,
    /** Last seq the client consumed before this reconnection. */
    public val lastEventSeq: Long,
    /** `true` when `oldestSeq > lastEventSeq + 1`, meaning some events are irrecoverably lost. */
    public val hasGap: Boolean,
)
