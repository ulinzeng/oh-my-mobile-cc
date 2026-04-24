package io.ohmymobilecc.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Android actual for [Ed25519] — identical to the JVM actual since bcprov-jdk18on
 * is bytecode-compatible with Android API 26+. Kept as a separate source set so
 * the Android team can later migrate to `java.security.Signature("Ed25519")`
 * once minSdk rises to 34 without touching the JVM actual.
 */
public actual object Ed25519 {
    public actual fun keypair(seed: ByteArray): Ed25519KeyPair {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes, got ${seed.size}" }
        val privateParams = Ed25519PrivateKeyParameters(seed, 0)
        val publicKey = privateParams.generatePublicKey().encoded
        val secretKey = ByteArray(64)
        seed.copyInto(secretKey, destinationOffset = 0)
        publicKey.copyInto(secretKey, destinationOffset = 32)
        return Ed25519KeyPair(secretKey = secretKey, publicKey = publicKey)
    }

    public actual fun sign(
        secretKey: ByteArray,
        message: ByteArray,
    ): ByteArray {
        require(secretKey.size == 64) { "Ed25519 secret key must be 64 bytes (seed||pk), got ${secretKey.size}" }
        val seed = secretKey.copyOfRange(0, 32)
        val privateParams = Ed25519PrivateKeyParameters(seed, 0)
        val signer = Ed25519Signer()
        signer.init(true, privateParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    public actual fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != 32) return false
        if (signature.size != 64) return false
        val publicParams = Ed25519PublicKeyParameters(publicKey, 0)
        val verifier = Ed25519Signer()
        verifier.init(false, publicParams)
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }
}
