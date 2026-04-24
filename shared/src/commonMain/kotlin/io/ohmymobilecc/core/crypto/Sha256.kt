package io.ohmymobilecc.core.crypto

/**
 * Platform-abstracted SHA-256.
 *
 * Available on every target (unlike [Ed25519], whose iOS actual is stubbed
 * in W1.5). Implementations are thin wrappers over the OS digest primitive:
 *
 *  - JVM / Android: `java.security.MessageDigest.getInstance("SHA-256")`
 *  - iOS: `CC_SHA256` from `platform.CoreCrypto` (CommonCrypto, always shipped)
 *
 * Used by [io.ohmymobilecc.core.pairing.DeviceId] to derive a 16-byte prefix
 * from an Ed25519 public key.
 */
public expect fun sha256(bytes: ByteArray): ByteArray
