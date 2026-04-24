package io.ohmymobilecc.relay.pairing

import io.ohmymobilecc.core.crypto.RandomSource
import io.ohmymobilecc.core.pairing.DeviceId
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the pairing flow:
 *
 * - [issueCode] — emit a fresh 6-digit code; caller is expected to print it
 *   to stdout / render it as a QR (`relay-cli pair` ships in W1.5 task 13).
 * - [redeem] — consume a code and register the client's Ed25519 public key.
 *   One-shot: a given code may be redeemed at most once, and only within
 *   the 5-minute TTL.
 * - [revoke] — mark a paired device revoked; live-evict on the server side
 *   is done by `RelayServer` when it next sees a connection from that id.
 *
 * Thread-safe: [issued] is a [ConcurrentHashMap] and `remove` is atomic.
 */
public class PairingService(
    private val clock: ClockSeam,
    private val random: RandomSource,
    private val registry: PubkeyRegistry,
) {
    private val issued = ConcurrentHashMap<String, PairingCode>()

    @Suppress("MagicNumber") // byte-masking + fixed 6-digit decimal padding
    public fun issueCode(): PairingCode {
        val bytes = random.nextBytes(4)
        val n =
            ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        val digits = ((n.toLong() and 0xFFFFFFFFL) % 1_000_000L).toString().padStart(6, '0')
        val code = PairingCode(digits = digits, issuedAtMs = clock.nowMs())
        issued[digits] = code
        return code
    }

    public fun redeem(
        digits: String,
        publicKey: ByteArray,
    ): DeviceId {
        val code = issued.remove(digits) ?: error("unknown pairing code")
        check(code.isValid(clock.nowMs())) { "pairing code expired" }
        val deviceId = DeviceId.fromPublicKey(publicKey)
        registry.register(deviceId, publicKey, clock.nowMs())
        return deviceId
    }

    public fun revoke(deviceId: DeviceId) {
        // No-op if the device was never registered (or already pruned); the
        // registry's `revoke` is idempotent via computeIfPresent.
        registry.revoke(deviceId, clock.nowMs())
    }

    /**
     * `true` iff [digits] is currently awaiting redeem. Used by
     * `relay-cli pair` to poll for redemption without consuming the code.
     */
    public fun peekIssued(digits: String): Boolean = issued.containsKey(digits)
}
