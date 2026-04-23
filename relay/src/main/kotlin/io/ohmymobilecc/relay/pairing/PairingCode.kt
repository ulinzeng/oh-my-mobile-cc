package io.ohmymobilecc.relay.pairing

/**
 * One-shot 6-digit pairing code per `openspec/specs/pairing/spec.md`
 * §6 位一次性配对码. The 5-minute TTL is enforced via [isValid]; the
 * one-shot consumption is enforced by [PairingService].
 */
public data class PairingCode(
    val digits: String,
    val issuedAtMs: Long,
) {
    init {
        @Suppress("MagicNumber") // spec pin: 6-digit codes
        require(digits.length == 6 && digits.all { it.isDigit() }) {
            "pairing code must be 6 digits, got '$digits'"
        }
    }

    /** Valid if now is >= issuedAt and < issuedAt + TTL. */
    public fun isValid(nowMs: Long): Boolean = nowMs >= issuedAtMs && (nowMs - issuedAtMs) < TTL_MS

    public companion object {
        /** 5-minute TTL per pairing spec. */
        public const val TTL_MS: Long = 5L * 60L * 1000L
    }
}
