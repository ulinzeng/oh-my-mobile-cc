package io.ohmymobilecc.core.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
public actual fun platformSecureRandom(): RandomSource =
    object : RandomSource {
        override fun nextBytes(size: Int): ByteArray {
            val buf = ByteArray(size)
            if (size == 0) return buf
            buf.usePinned { pinned ->
                val status = SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
                require(status == 0) { "SecRandomCopyBytes failed: status=$status" }
            }
            return buf
        }
    }
