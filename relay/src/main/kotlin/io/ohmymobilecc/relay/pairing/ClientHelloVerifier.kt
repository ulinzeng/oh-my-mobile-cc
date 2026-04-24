package io.ohmymobilecc.relay.pairing

import io.ohmymobilecc.core.crypto.Base64Url
import io.ohmymobilecc.core.crypto.Ed25519
import io.ohmymobilecc.core.pairing.DeviceId
import io.ohmymobilecc.core.pairing.HelloCodec
import io.ohmymobilecc.core.protocol.WireMessage
import kotlin.math.abs

/**
 * Outcome of verifying a [WireMessage.ClientHello] frame.
 *
 * `reason` strings on [Err] match the `HelloErr.reason` alphabet specified in
 * `openspec/specs/pairing/spec.md` §Handshake 结果: one of `skew`, `nonce`,
 * `unpaired`, `revoked`, `sig`.
 */
public sealed interface VerifyResult {
    public data class Ok(
        val deviceId: DeviceId,
    ) : VerifyResult

    public data class Err(
        val reason: String,
    ) : VerifyResult
}

/**
 * Pure-logic verifier for the relay-side `ClientHello` frame.
 *
 * Composes, in order:
 *
 * 1. **Skew**: reject if `|now - hello.timestampMs| > skewToleranceMs`.
 *    Boundary is inclusive of the tolerance (`abs == 60_000ms` still OK).
 * 2. **Pairing**: look up [DeviceId] in [PubkeyRegistry]; missing → `unpaired`.
 * 3. **Revocation**: registered but `revokedAtMs != null` → `revoked`.
 * 4. **Nonce**: `NonceCache.offer` must accept; replay → `nonce`.
 * 5. **Signature**: decode base64url sig, require 64 bytes, run
 *    [Ed25519.verify] over the canonical `sessionId|ts|nonce`.
 *
 * No I/O, no coroutine context — callers run this inside whatever context
 * the transport lives in (Ktor WS, tests, etc.).
 */
public class ClientHelloVerifier(
    private val registry: PubkeyRegistry,
    private val nonceCache: NonceCache,
    private val clock: ClockSeam,
    private val skewToleranceMs: Long = DEFAULT_SKEW_TOLERANCE_MS,
) {
    // Guard-clause chain: one `return Err(reason)` per validation stage
    // reads clearer than nesting — hence the per-function ReturnCount suppression.
    @Suppress("ReturnCount")
    public fun verify(hello: WireMessage.ClientHello): VerifyResult {
        val now = clock.nowMs()
        if (abs(now - hello.timestampMs) > skewToleranceMs) return VerifyResult.Err("skew")

        val device =
            registry.find(DeviceId(hello.deviceId))
                ?: return VerifyResult.Err("unpaired")
        if (device.revokedAtMs != null) return VerifyResult.Err("revoked")

        if (!nonceCache.offer(hello.nonce, now)) return VerifyResult.Err("nonce")

        val canonical =
            HelloCodec
                .canonicalSigningInput(hello.sessionId, hello.timestampMs, hello.nonce)
                .encodeToByteArray()

        val sigBytes =
            runCatching { Base64Url.decode(hello.sig) }
                .getOrElse { return VerifyResult.Err("sig") }
        if (sigBytes.size != ED25519_SIGNATURE_BYTES) return VerifyResult.Err("sig")

        val valid = Ed25519.verify(device.publicKey, canonical, sigBytes)
        return if (valid) VerifyResult.Ok(device.deviceId) else VerifyResult.Err("sig")
    }

    private companion object {
        const val DEFAULT_SKEW_TOLERANCE_MS: Long = 60_000L
        const val ED25519_SIGNATURE_BYTES: Int = 64
    }
}
