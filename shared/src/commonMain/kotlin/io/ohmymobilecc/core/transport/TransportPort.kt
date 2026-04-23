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
    ): Result<TransportSession>

    /** Release any pooled resources (http engine, etc.). Idempotent. */
    public suspend fun shutdown()
}

/**
 * Live relay session handed out by [TransportPort.connect] on success.
 *
 * `incoming` emits every post-handshake `WireMessage` the relay pushes.
 * Implementations MUST drop unparseable frames with a warn log (per
 * protocol § 传输语义) rather than terminating the flow.
 */
public interface TransportSession {
    public val incoming: Flow<WireMessage>

    public suspend fun send(message: WireMessage)

    public suspend fun close()
}
