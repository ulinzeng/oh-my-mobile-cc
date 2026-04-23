package io.ohmymobilecc.core.crypto

/**
 * URL-safe base64 without padding (RFC 4648 §5).
 *
 * Alphabet: `A-Z a-z 0-9 - _`. No `+`, no `/`, no `=` — safe to embed in JSON
 * strings, URL query parameters, and HTTP headers without escaping.
 *
 * This module's pairing protocol uses base64url for `deviceId`, `nonce`, and
 * `sig` fields of the `ClientHello` frame; decisions elsewhere in the codebase
 * (JWT-style tokens, if we ever add them) are free to adopt this helper too.
 */
public object Base64Url {
    private const val ALPHABET: String =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    public fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n =
                (bytes[i].toInt() and 0xFF shl 16) or
                    (bytes[i + 1].toInt() and 0xFF shl 8) or
                    (bytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n ushr 18) and 0x3F])
            sb.append(ALPHABET[(n ushr 12) and 0x3F])
            sb.append(ALPHABET[(n ushr 6) and 0x3F])
            sb.append(ALPHABET[n and 0x3F])
            i += 3
        }
        val rem = bytes.size - i
        if (rem == 1) {
            val n = bytes[i].toInt() and 0xFF shl 16
            sb.append(ALPHABET[(n ushr 18) and 0x3F])
            sb.append(ALPHABET[(n ushr 12) and 0x3F])
        } else if (rem == 2) {
            val n =
                (bytes[i].toInt() and 0xFF shl 16) or
                    (bytes[i + 1].toInt() and 0xFF shl 8)
            sb.append(ALPHABET[(n ushr 18) and 0x3F])
            sb.append(ALPHABET[(n ushr 12) and 0x3F])
            sb.append(ALPHABET[(n ushr 6) and 0x3F])
        }
        return sb.toString()
    }

    public fun decode(s: String): ByteArray {
        if (s.isEmpty()) return ByteArray(0)
        val out = ByteArray((s.length * 6 + 7) / 8)
        var bits = 0
        var acc = 0
        var o = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "invalid base64url char: $c" }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = ((acc ushr bits) and 0xFF).toByte()
            }
        }
        return if (o == out.size) out else out.copyOf(o)
    }
}
