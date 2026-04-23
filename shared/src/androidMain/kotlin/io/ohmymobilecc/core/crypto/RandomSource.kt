package io.ohmymobilecc.core.crypto

import java.security.SecureRandom

// Android ships java.security.SecureRandom from API 1+; same backend as JVM.
public actual fun platformSecureRandom(): RandomSource {
    val sr = SecureRandom()
    return object : RandomSource {
        override fun nextBytes(size: Int): ByteArray {
            val out = ByteArray(size)
            sr.nextBytes(out)
            return out
        }
    }
}
