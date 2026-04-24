package io.ohmymobilecc.relay.pairing

import io.ohmymobilecc.core.crypto.RandomSource
import io.ohmymobilecc.core.pairing.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PairingServiceTest {
    private val clock = FakeClock(startMs = 1_000_000_000L)
    private val random = FakeRandom(pattern = byteArrayOf(0, 0, 0, 0x2A)) // deterministic digits
    private val registry = InMemoryPubkeyRegistry()
    private val svc = PairingService(clock = clock, random = random, registry = registry)

    @Test
    fun issueCode_returns_six_digits_within_TTL() {
        val code = svc.issueCode()
        assertEquals(6, code.digits.length)
        // Every char is a digit — enforced by PairingCode init — but re-assert.
        assertEquals(true, code.digits.all { it.isDigit() })
        assertEquals(true, code.isValid(clock.nowMs()))
    }

    @Test
    fun redeem_registers_pubkey_and_consumes_code() {
        val code = svc.issueCode()
        val pk = ByteArray(32) { 0x11 }
        val deviceId = svc.redeem(code.digits, publicKey = pk)
        assertNotNull(registry.find(deviceId))
        // Second redeem of same code must fail — one-shot semantics from spec.
        assertFailsWith<IllegalStateException> {
            svc.redeem(code.digits, publicKey = pk)
        }
    }

    @Test
    fun redeem_unknown_code_rejects() {
        assertFailsWith<IllegalStateException> {
            svc.redeem(digits = "999999", publicKey = ByteArray(32))
        }
    }

    @Test
    fun redeem_after_TTL_rejects() {
        val code = svc.issueCode()
        clock.advanceMs(5 * 60 * 1000L + 1)
        assertFailsWith<IllegalStateException> {
            svc.redeem(code.digits, publicKey = ByteArray(32))
        }
    }

    @Test
    fun redeem_is_injective_per_pubkey() {
        val code1 = svc.issueCode()
        val pkA = ByteArray(32) { 0x11 }
        val idA = svc.redeem(code1.digits, publicKey = pkA)

        val code2 = svc.issueCode()
        val pkB = ByteArray(32) { 0x22 }
        val idB = svc.redeem(code2.digits, publicKey = pkB)

        assertEquals(DeviceId.fromPublicKey(pkA), idA)
        assertEquals(DeviceId.fromPublicKey(pkB), idB)
        assertEquals(true, idA != idB)
    }

    @Test
    fun revoke_flips_registry_flag() {
        val code = svc.issueCode()
        val pk = ByteArray(32) { 0x22 }
        val deviceId = svc.redeem(code.digits, publicKey = pk)
        assertNull(registry.find(deviceId)?.revokedAtMs)
        svc.revoke(deviceId)
        assertEquals(clock.nowMs(), registry.find(deviceId)?.revokedAtMs)
    }

    @Test
    fun revoke_unknown_deviceId_is_no_op() {
        // No exception; no effect on other devices. Useful so a best-effort
        // CLI revoke doesn't crash on stale ids.
        svc.revoke(DeviceId("does-not-exist"))
    }
}

// Test doubles scoped to this package.

internal class FakeClock(
    startMs: Long,
) : ClockSeam {
    private var cur: Long = startMs

    override fun nowMs(): Long = cur

    fun advanceMs(delta: Long) {
        cur += delta
    }
}

internal class FakeRandom(
    private val pattern: ByteArray,
) : RandomSource {
    private var i = 0

    override fun nextBytes(size: Int): ByteArray = ByteArray(size) { pattern[(i++) % pattern.size] }
}
