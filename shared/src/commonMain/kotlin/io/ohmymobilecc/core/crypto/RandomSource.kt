package io.ohmymobilecc.core.crypto

/**
 * Platform-abstracted secure random byte source.
 *
 * Used for:
 *  - pairing-code generation on relay (via `PairingService`)
 *  - `ClientHello` nonces + Ed25519 keypair seeds on mobile
 *
 * Tests inject a deterministic fake (`FakeRandom`) instead of calling
 * [platformSecureRandom]; production code calls [platformSecureRandom].
 */
public interface RandomSource {
    /** Returns [size] bytes of CSPRNG output. */
    public fun nextBytes(size: Int): ByteArray
}

/**
 * Returns a [RandomSource] backed by the platform's CSPRNG.
 *
 *  - JVM / Android: `java.security.SecureRandom` (OS-managed seed)
 *  - iOS: `SecRandomCopyBytes(kSecRandomDefault, ...)`
 */
public expect fun platformSecureRandom(): RandomSource
