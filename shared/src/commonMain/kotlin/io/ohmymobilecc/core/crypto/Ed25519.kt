package io.ohmymobilecc.core.crypto

/**
 * Ed25519 keypair.
 *
 * `secretKey` layout follows RFC 8032 §5.1.5: `seed (32 bytes) || publicKey (32 bytes)` = 64 bytes.
 * Passing the 32-byte seed back into [Ed25519.keypair] is idempotent.
 */
public data class Ed25519KeyPair(
    val secretKey: ByteArray,
    val publicKey: ByteArray,
) {
    init {
        require(secretKey.size == 64) { "Ed25519 secret key must be 64 bytes (seed||pk), got ${secretKey.size}" }
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes, got ${publicKey.size}" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ed25519KeyPair) return false
        return secretKey.contentEquals(other.secretKey) && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = 31 * secretKey.contentHashCode() + publicKey.contentHashCode()
}

/**
 * Ed25519 signature primitive, per RFC 8032.
 *
 * Implementation strategy locked in by OpenSpec change `add-ed25519-platform-crypto-impl`:
 * - JVM / Android actuals use **BouncyCastle 1.78+**.
 * - iOS actual is a W1.5 stub that throws [NotImplementedError]; real iOS actual lands in W2.1.
 * - Pure-Kotlin reimplementation of field / group arithmetic is prohibited without a superseding proposal.
 *
 * Every actual MUST pass RFC 8032 §7.1 test vectors (see
 * `shared/src/commonTest/.../Ed25519Test.kt`).
 */
public expect object Ed25519 {
    /**
     * Derive an Ed25519 keypair from a 32-byte seed per RFC 8032 §5.1.5.
     *
     * @param seed 32 random bytes
     * @return [Ed25519KeyPair] whose [Ed25519KeyPair.publicKey] deterministically derives from [seed]
     */
    public fun keypair(seed: ByteArray): Ed25519KeyPair

    /**
     * Sign [message] with [secretKey] using pure Ed25519 (RFC 8032 §5.1.6).
     *
     * @param secretKey 64-byte secret key `seed || publicKey` as returned by [keypair]
     * @param message arbitrary bytes (may be empty)
     * @return 64-byte detached signature
     */
    public fun sign(
        secretKey: ByteArray,
        message: ByteArray,
    ): ByteArray

    /**
     * Verify [signature] against [publicKey] over [message] per RFC 8032 §5.1.7.
     *
     * @return true iff the signature is valid; never throws on bad signatures, only on malformed inputs
     */
    public fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean
}
