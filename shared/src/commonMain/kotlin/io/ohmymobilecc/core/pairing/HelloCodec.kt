package io.ohmymobilecc.core.pairing

/**
 * Canonical encoders / parsers for the pairing handshake.
 *
 * Currently exposes only the signing-input canonicalizer — the wire shapes
 * themselves live in [io.ohmymobilecc.core.protocol.WireMessage.ClientHello]
 * / `HelloOk` / `HelloErr` and use the standard [ProtocolJson] codec.
 */
public object HelloCodec {
    /**
     * Canonical signing input per `openspec/specs/pairing/spec.md` §Ed25519
     * 会话签名. Shape: `sessionId || "|" || timestampMs || "|" || nonce`.
     *
     * Precondition: none of [sessionId], [nonce] contains `|`. In practice
     * `sessionId` is an opaque pairing-flow identifier and `nonce` is
     * base64url-encoded random bytes, so neither emits `|`.
     */
    public fun canonicalSigningInput(
        sessionId: String,
        timestampMs: Long,
        nonce: String,
    ): String = "$sessionId|$timestampMs|$nonce"
}
