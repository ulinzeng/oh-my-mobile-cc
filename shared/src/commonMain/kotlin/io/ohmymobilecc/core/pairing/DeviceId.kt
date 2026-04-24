package io.ohmymobilecc.core.pairing

import io.ohmymobilecc.core.crypto.Base64Url
import io.ohmymobilecc.core.crypto.sha256
import kotlin.jvm.JvmInline

/**
 * Short, stable, URL-safe identifier for a paired device.
 *
 * Derivation (per [openspec/specs/pairing/spec.md]): take the first 16 bytes of
 * `SHA-256(ed25519_public_key)` and base64url-encode without padding. This
 * gives a 22-character string that is deterministic per public key,
 * printable, and safe to embed in WS frames and URLs.
 *
 * Why 16 bytes (128 bits): probability of birthday-collision across the
 * single-user device fleet we target is negligible; collision doesn't grant
 * impersonation (the pubkey itself still gates `verify`), only UI confusion.
 */
@JvmInline
public value class DeviceId(
    public val raw: String,
) {
    public companion object {
        /** 16-byte sha256 prefix of the Ed25519 public key, base64url encoded. */
        public fun fromPublicKey(publicKey: ByteArray): DeviceId {
            require(publicKey.size == 32) {
                "Ed25519 public key must be 32 bytes, got ${publicKey.size}"
            }
            val digest = sha256(publicKey)
            return DeviceId(Base64Url.encode(digest.copyOf(16)))
        }
    }
}
