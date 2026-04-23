package io.ohmymobilecc.core.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256

/**
 * iOS SHA-256 via CommonCrypto — shipped in every iOS version we support
 * (unlike CryptoKit's `SHA256`, which requires iOS 13+ and brings Swift
 * interop surface we don't want here).
 */
@OptIn(ExperimentalForeignApi::class)
public actual fun sha256(bytes: ByteArray): ByteArray {
    val out = ByteArray(32) // CC_SHA256_DIGEST_LENGTH
    // CC_SHA256 tolerates a null data pointer only when len == 0.
    val input = if (bytes.isEmpty()) ByteArray(1) else bytes
    input.usePinned { inPin ->
        out.usePinned { outPin ->
            CC_SHA256(
                inPin.addressOf(0),
                bytes.size.convert(),
                outPin.addressOf(0).reinterpret<UByteVar>(),
            )
        }
    }
    return out
}
