package io.ohmymobilecc.core.transport

/**
 * Typed failure modes surfaced through `Result.failure` by
 * [TransportPort.connect] and session send/receive methods.
 *
 * Closed sealed hierarchy per project convention — downstream `when`
 * branches stay exhaustive as new cases arrive.
 */
public sealed class RelayError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /**
     * Relay replied `HelloErr(reason)`. [reason] is one of the 8 values
     * defined in `openspec/specs/pairing/spec.md` §Handshake 结果.
     */
    public class Rejected(
        public val reason: String,
    ) : RelayError("relay rejected hello: $reason")

    /**
     * Relay sent a frame the client did not expect at this stage of the
     * handshake (e.g. any non-HelloOk / non-HelloErr frame before the
     * first HelloOk) or closed the WS unexpectedly.
     */
    public class ProtocolViolation(
        detail: String = "unexpected frame",
    ) : RelayError("relay protocol violation: $detail")

    /** Socket/HTTP/DNS-level failure. Retain [cause] for caller diagnostics. */
    public class Network(
        cause: Throwable,
    ) : RelayError("relay network error: ${cause.message}", cause)
}
