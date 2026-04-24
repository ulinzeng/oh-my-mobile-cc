package io.ohmymobilecc.core.transport

import io.ohmymobilecc.core.pairing.DeviceId
import io.ohmymobilecc.core.protocol.WireMessage
import kotlinx.coroutines.flow.Flow

/**
 * Endpoint coordinates for a relay WS. Pure value type — no URL scheme
 * reasoning is embedded here; transport implementations decide whether
 * to upgrade the scheme to `ws` / `wss` based on platform policy.
 */
public data class TransportEndpoint(
    val host: String,
    val port: Int,
    val sessionId: String,
    val pathPrefix: String = "/ws",
)

/**
 * Paired device identity: the Ed25519 keys + the derived [DeviceId] used
 * to prove possession during [TransportPort.connect]'s handshake.
 *
 * `secretKey` follows the 64-byte `seed || publicKey` layout returned by
 * [io.ohmymobilecc.core.crypto.Ed25519.keypair]; callers MUST zero it
 * when the session ends (not done here — handled by the secure-storage
 * adapter in androidMain / iosMain).
 */
public data class DeviceIdentity(
    val deviceId: DeviceId,
    val publicKey: ByteArray,
    val secretKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceIdentity) return false
        return deviceId == other.deviceId &&
            publicKey.contentEquals(other.publicKey) &&
            secretKey.contentEquals(other.secretKey)
    }

    override fun hashCode(): Int {
        var h = deviceId.hashCode()
        h = 31 * h + publicKey.contentHashCode()
        h = 31 * h + secretKey.contentHashCode()
        return h
    }
}

/**
 * Optional parameters for [TransportPort.connect] that control
 * reconnection-aware handshake behavior. The default (all-null) instance
 * yields the same behavior as W1.0 first-connect.
 */
public data class ConnectOptions(
    /** Highest event seq the client has already consumed. The relay uses
     *  this to replay missed events after a reconnection handshake. */
    public val lastEventSeq: Long? = null,
)

/**
 * A single frame received from the relay, optionally stamped with the
 * server-assigned monotonic sequence number. The [seq] is `null` for
 * control frames (e.g. `ReplayEnd`, `SessionEnded`) that the relay does
 * not stamp.
 */
public data class ReceivedFrame(
    public val seq: Long? = null,
    public val message: WireMessage,
)

/**
 * Port abstraction the mobile app + any relay test harness programs
 * against. Implementations live per-platform in `jvmMain` / `androidMain`
 * / `iosMain` — `jvmMain`'s `KtorRelayClient` is the only impl that
 * ships with W1.5.
 *
 * Contract (per `openspec/specs/protocol/spec.md`):
 *  - [connect] suspends until the relay answers `HelloOk`, `HelloErr`,
 *    closes, or emits a non-hello frame as the first reply.
 *  - Success → [Result.success] with a [TransportSession] whose
 *    `incoming` flow begins AFTER the handshake.
 *  - `HelloErr(reason)` → [Result.failure] wrapping [RelayError.Rejected].
 *  - Any other protocol deviation (early non-hello frame, close before
 *    hello, unparseable first frame) → [Result.failure] wrapping
 *    [RelayError.ProtocolViolation].
 */
public interface TransportPort {
    public suspend fun connect(
        endpoint: TransportEndpoint,
        identity: DeviceIdentity,
        options: ConnectOptions = ConnectOptions(),
    ): Result<TransportSession>

    /** Release any pooled resources (http engine, etc.). Idempotent. */
    public suspend fun shutdown()
}

/**
 * Live relay session handed out by [TransportPort.connect] on success.
 *
 * [helloOk] carries the handshake metadata (server time, seq range) from
 * the relay's `HelloOk` reply — callers use it for skew detection and
 * replay-progress tracking.
 *
 * [incoming] emits every post-handshake frame the relay pushes, wrapped
 * in [ReceivedFrame] so the caller can track per-event sequence numbers.
 * Implementations MUST drop unparseable frames with a warn log (per
 * protocol § 传输语义) rather than terminating the flow. The flow
 * completes when the underlying connection closes.
 */
public interface TransportSession {
    public val helloOk: WireMessage.HelloOk

    public val incoming: Flow<ReceivedFrame>

    public suspend fun send(message: WireMessage)

    public suspend fun close()
}
